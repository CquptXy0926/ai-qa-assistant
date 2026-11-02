package com.itheima.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * RAG 检索与切分参数
 *
 * @param topK             BM25 召回数量
 * @param finalK           进入 prompt 的最终片段数
 * @param chunkSize        切分长度（字符）
 * @param chunkOverlap     切分重叠（字符）
 * @param minScore         BM25 绝对分阈值（辅助判据，主判据为查询词覆盖率）
 * @param scoreGapRatio    相关性断层比例：片段得分需 ≥ top1 得分 × 此值才保留，越小越宽松
 * @param queryStopWords   额外疑问词/虚词（在内置词表基础上追加，用于提取问题核心词做标题相关性判定）
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int topK,
        int finalK,
        int chunkSize,
        int chunkOverlap,
        double minScore,
        @DefaultValue("0.5") double scoreGapRatio,
        @DefaultValue List<String> queryStopWords) {
}
