package com.itheima.qa.rag;

import com.itheima.qa.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索服务：BM25 召回 Top-K → 多样性截断（单文档最多 2 片）→ 取 Final-K；
 * top1 得分低于置信度阈值视为未命中（由上层走兜底话术）。
 */
@Service
public class RetrievalService {

    /** 内置疑问词/虚词基表：从问题中剔除后剩下的才是真正区分主题的核心词。
     *  可通过 app.rag.query-stop-words 在此基础上追加。 */
    private static final Set<String> BASE_STOP = Set.of(
            "什", "么", "是", "的", "了", "呢", "吗", "啊", "这", "那", "有", "和", "与", "及",
            "请", "问", "想", "要", "给", "我", "你", "它", "他", "她", "们", "在", "就", "也",
            "什么", "么是", "是什", "请问", "如何", "怎么", "怎样", "介绍", "概念", "含义",
            "意思", "定义", "哪些", "哪个", "哪里", "为什么", "作用", "用途", "是什么", "干什么");

    /** 提供给 prompt 与引用展示的命中片段 */
    public record Passage(int refNo, Long chunkId, Long docId, String fileName,
                          String location, String content, double score) {}

    public record Result(boolean hit, List<Passage> passages) {}

    private final Bm25Index index;
    private final RagProperties props;
    /** 生效的疑问词表 = 内置基表 ∪ 配置追加 */
    private final Set<String> stopWords;

    public RetrievalService(Bm25Index index, RagProperties props) {
        this.index = index;
        this.props = props;
        Set<String> sw = new HashSet<>(BASE_STOP);
        if (props.queryStopWords() != null) {
            sw.addAll(props.queryStopWords());
        }
        this.stopWords = Set.copyOf(sw);
    }

    public Result retrieve(List<Long> kbIds, String query) {
        List<Bm25Index.Hit> raw = index.search(kbIds, query, props.topK());
        if (raw.isEmpty()) {
            return new Result(false, List.of());
        }
        // 双重置信度：以查询词元覆盖率为主判据（解决小语料下 BM25 绝对分失真），
        // 覆盖率 ≥0.5 直接命中；覆盖率一般时要求 BM25 分数同时达标，否则走兜底
        Bm25Index.Hit top = raw.get(0);
        double coverage = coverage(query, top.content());
        boolean confident = coverage >= 0.5 || (coverage >= 0.3 && top.score() >= props.minScore());
        if (!confident) {
            return new Result(false, List.of());
        }
        // 相关性断层裁剪：raw 已按分数降序，只保留与 top1 得分差距不大的片段。
        // 避免把正文里"顺带提到关键词"的弱相关章节（如问 Spring Boot 却召回 CI/CD 一节）
        // 塞进答案，从而稀释精度。top1 必然保留。
        double gapThreshold = top.score() * props.scoreGapRatio();
        // 多样性控制：同一文档最多保留 2 个片段，避免单文档刷屏
        Map<Long, Integer> perDoc = new HashMap<>();
        List<Bm25Index.Hit> picked = new ArrayList<>();
        for (Bm25Index.Hit h : raw) {
            if (h.score() < gapThreshold) {
                break;
            }
            int c = perDoc.merge(h.docId(), 1, Integer::sum);
            if (c <= 2) {
                picked.add(h);
            }
            if (picked.size() >= props.finalK()) {
                break;
            }
        }
        List<Passage> passages = new ArrayList<>();
        // 标题相关性闸门：问题核心词（去掉疑问词后）命中小节标题才算真正相关。
        // 剔除"正文顺带提及"造成的弱相关章节（如问 Bean 却召回标题为"事务"、正文提到 Bean 的一节）。
        // 无标题的片段（txt/pdf）不受此闸门约束，仍靠得分断层裁剪兜底。
        Set<String> core = coreQueryTerms(query, stopWords);
        int refNo = 0;
        for (int i = 0; i < picked.size(); i++) {
            Bm25Index.Hit h = picked.get(i);
            if (i > 0 && !core.isEmpty() && !titleHasCore(h.content(), core)) {
                continue;
            }
            passages.add(new Passage(++refNo, h.chunkId(), h.docId(), h.fileName(),
                    h.location(), h.content(), Math.round(h.score() * 100) / 100.0));
        }
        return new Result(true, passages);
    }

    /** 提取问题核心词元：分词后剔除疑问词/虚词 */
    static Set<String> coreQueryTerms(String query, Set<String> stopWords) {
        Set<String> core = new HashSet<>();
        for (String t : Tokenizer.tokenize(query)) {
            if (!stopWords.contains(t)) {
                core.add(t);
            }
        }
        return core;
    }

    /** 片段首行若为 markdown 标题，判断标题是否命中任一核心词；非标题片段一律放行 */
    static boolean titleHasCore(String content, Set<String> core) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        int nl = content.indexOf('\n');
        String firstLine = (nl < 0 ? content : content.substring(0, nl)).trim();
        if (!firstLine.startsWith("#")) {
            return true; // 无标题结构，不做限制
        }
        String title = firstLine.replaceAll("^#+\\s*", "");
        for (String t : Tokenizer.tokenize(title)) {
            if (core.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 查询词元（去重）在片段中的覆盖率 0~1 */
    static double coverage(String query, String content) {
        var qt = new java.util.HashSet<>(Tokenizer.tokenize(query));
        if (qt.isEmpty()) {
            return 0;
        }
        long hit = qt.stream().filter(t -> content.contains(t)).count();
        return (double) hit / qt.size();
    }
}
