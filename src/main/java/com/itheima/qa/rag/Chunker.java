package com.itheima.qa.rag;

import com.itheima.qa.config.RagProperties;
import com.itheima.qa.model.DocChunk;
import com.itheima.qa.model.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切分器：
 * - markdown 文件：按 #/##/### 标题切"小节",每个小节（标题+正文）作为一个语义完整的 chunk，
 *   超过 chunkSize 时再按句子边界二次切分；
 * - 其他格式：在解析段内部按 chunkSize 滑动窗口切分，保留 chunkOverlap 重叠，
 *   优先在句子边界（。！？\n）断开。
 * 【为什么标题感知切分】QA 场景下"一个 ### 小节 = 一个知识点"，把整节作为 chunk
 * 能让 BM25 命中时直接拿到完整答案，避免旧方案"512 字符硬切把多个主题混在一个 chunk"
 * 导致检索结果里全是标题行、没有正文。
 */
@Component
public class Chunker {

    /** markdown 标题行：1~6 个 # 开头，后面跟空格 */
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6} +\\S");
    private static final int MAX_SECTION = 1200; // 单个小节超过此长度则二次切分

    private final RagProperties props;

    public Chunker(RagProperties props) {
        this.props = props;
    }

    public List<DocChunk> chunk(Document doc, List<DocumentParser.Segment> segments) {
        boolean md = doc.getFileName() != null && doc.getFileName().toLowerCase().endsWith(".md");
        List<DocChunk> chunks = new ArrayList<>();
        int index = 0;
        for (var seg : segments) {
            String text = normalize(seg.text());
            if (md) {
                // 1) 按标题切成若干"小节"（每节含标题行 + 后续正文）
                List<String> sections = splitMarkdownSections(text);
                for (String sec : sections) {
                    // 2) 小节过长时再按 chunkSize 二次滑窗，避免超大 chunk 拉低 BM25 精度
                    if (sec.length() <= MAX_SECTION) {
                        String piece = sec.trim();
                        if (!piece.isEmpty()) {
                            chunks.add(newChunk(doc, index++, seg.location(), piece));
                        }
                    } else {
                        index = slideWindowChunks(sec, doc, seg.location(), index, chunks);
                    }
                }
            } else {
                index = slideWindowChunks(text, doc, seg.location(), index, chunks);
            }
        }
        return chunks;
    }

    /** 通用滑动窗口切分（原逻辑，用于非 md 或超长小节） */
    private int slideWindowChunks(String text, Document doc, String location, int index, List<DocChunk> out) {
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(start + props.chunkSize(), text.length());
            int cut = sentenceBoundary(text, start, end);
            String piece = text.substring(start, cut).trim();
            if (!piece.isEmpty()) {
                out.add(newChunk(doc, index++, location, piece));
            }
            if (cut >= text.length()) {
                break;
            }
            start = Math.max(cut - props.chunkOverlap(), start + 1);
        }
        return index;
    }

    private static DocChunk newChunk(Document doc, int idx, String location, String content) {
        DocChunk c = new DocChunk();
        c.setDocument(doc);
        c.setChunkIndex(idx);
        c.setLocation(location);
        c.setContent(content);
        return c;
    }

    /**
     * 按 markdown 标题切小节：每个 ### 标题开始一个新小节，标题行 + 后续正文归为一节。
     * 文件开头到第一个标题之前的内容（如一级标题 + 引言）作为独立一节。
     */
    static List<String> splitMarkdownSections(String text) {
        List<String> sections = new ArrayList<>();
        java.util.regex.Matcher m = MD_HEADING.matcher(text);
        int last = 0;
        while (m.find()) {
            int headingStart = m.start();
            if (headingStart > last) {
                String pre = text.substring(last, headingStart).trim();
                if (!pre.isEmpty()) {
                    sections.add(pre);
                }
            }
            last = headingStart;
            // 找到下一个标题起点：当前 find 已经定位，循环继续时再处理
        }
        // 回退：上面循环只记录最后一个标题起点，需要重做以收集所有节
        sections.clear();
        List<Integer> starts = new ArrayList<>();
        m.reset();
        while (m.find()) {
            starts.add(m.start());
        }
        if (starts.isEmpty()) {
            // 没有标题，整段作为一节
            String t = text.trim();
            if (!t.isEmpty()) {
                sections.add(t);
            }
            return sections;
        }
        // 第一个标题之前的内容
        if (starts.get(0) > 0) {
            String pre = text.substring(0, starts.get(0)).trim();
            if (!pre.isEmpty()) {
                sections.add(pre);
            }
        }
        // 每个标题到下一个标题之间为一节
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int e = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            String sec = text.substring(s, e).trim();
            if (!sec.isEmpty()) {
                sections.add(sec);
            }
        }
        return sections;
    }

    private static String normalize(String text) {
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\n{3,}", "\n\n");
    }

    /** 在 [searchFrom, end] 内从后往前找句子边界，找不到退回硬切 */
    private static int sentenceBoundary(String text, int searchFrom, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        for (int i = end; i > searchFrom + (end - searchFrom) / 2; i--) {
            char ch = text.charAt(i - 1);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == ';' || ch == '；') {
                return i;
            }
        }
        return end;
    }
}
