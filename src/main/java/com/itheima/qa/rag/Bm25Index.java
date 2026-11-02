package com.itheima.qa.rag;

import com.itheima.qa.model.DocChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 内存索引：按知识库分桶，重启时从数据库切片表全量重建。
 * 中文以 1-gram/2-gram 近似词元，效果满足内部 QA 场景，零外部依赖。
 */
public class Bm25Index {

    /** 检索命中结果 */
    public record Hit(Long chunkId, Long docId, String fileName, String location,
                      String content, double score) {}

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private record IndexEntry(Hit hit, Map<String, Integer> tf, int len) {}

    private static class IndexBucket {
        final List<IndexEntry> entries = new ArrayList<>();
        final Map<String, Integer> df = new HashMap<>();
        double avgLen = 1;
    }

    private final Map<Long, IndexBucket> buckets = new HashMap<>();

    /**
     * 用给定切片集合重建某个知识库的索引。
     * 【标题加权】chunk 内容首行若是 markdown 标题（# 开头），该行的 token 在 tf 里额外计一次，
     * 让"问题命中关键词"的章节排在更前面——QA 场景下用户问的多是标题里的术语。
     */
    public synchronized void rebuild(Long kbId, List<DocChunk> chunks) {
        IndexBucket bucket = new IndexBucket();
        long totalLen = 0;
        for (DocChunk c : chunks) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> tokens = Tokenizer.tokenize(c.getContent());
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            // 标题行 token 加权 ×2
            String firstLine = firstLineOf(c.getContent());
            if (firstLine != null && firstLine.startsWith("#")) {
                for (String t : Tokenizer.tokenize(firstLine)) {
                    tf.merge(t, 1, Integer::sum);
                }
            }
            for (String t : tf.keySet()) {
                bucket.df.merge(t, 1, Integer::sum);
            }
            totalLen += tokens.size();
            bucket.entries.add(new IndexEntry(
                    new Hit(c.getId(), c.getDocument().getId(), c.getDocument().getFileName(),
                            c.getLocation(), c.getContent(), 0),
                    tf, tokens.size()));
        }
        bucket.avgLen = bucket.entries.isEmpty() ? 1 : (double) totalLen / bucket.entries.size();
        buckets.put(kbId, bucket);
    }

    private static String firstLineOf(String content) {
        if (content == null || content.isEmpty()) return null;
        int nl = content.indexOf('\n');
        return (nl < 0 ? content : content.substring(0, nl)).trim();
    }

    /** 多知识库联合检索，返回按 BM25 得分降序的前 topK 条 */
    public synchronized List<Hit> search(List<Long> kbIds, String query, int topK) {
        List<String> qTokens = Tokenizer.tokenize(query);
        if (qTokens.isEmpty()) {
            return List.of();
        }
        record Candidate(Hit hit, double score) {}
        List<Candidate> results = new ArrayList<>();
        for (Long kbId : kbIds) {
            IndexBucket bucket = buckets.get(kbId);
            if (bucket == null || bucket.entries.isEmpty()) {
                continue;
            }
            int n = bucket.entries.size();
            for (IndexEntry e : bucket.entries) {
                double score = 0;
                for (String t : qTokens) {
                    int f = e.tf().getOrDefault(t, 0);
                    if (f == 0) {
                        continue;
                    }
                    int dfq = bucket.df.getOrDefault(t, 0);
                    double idf = Math.log(1 + (n - dfq + 0.5) / (dfq + 0.5));
                    score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * e.len() / bucket.avgLen));
                }
                if (score > 0) {
                    results.add(new Candidate(e.hit(), score));
                }
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.stream()
                .limit(topK)
                .map(c -> new Hit(c.hit().chunkId(), c.hit().docId(), c.hit().fileName(),
                        c.hit().location(), c.hit().content(), c.score()))
                .toList();
    }

    public synchronized Set<Long> indexedKbIds() {
        return Set.copyOf(buckets.keySet());
    }
}
