package com.itheima.qa.rag;

import com.itheima.qa.config.RagProperties;
import com.itheima.qa.model.DocChunk;
import com.itheima.qa.model.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精度回归测试：用真实《常见IT术语回答问题.md》跑通
 * 解析 → 标题感知切分 → BM25 建索引 → 检索（含得分断层裁剪 + 标题相关性闸门）全链路，
 * 覆盖定义/项目用法/FAQ/多词等多种问法，确保"命中正确小节、剔除正文顺带提及的弱相关小节"。
 */
class RetrievalPrecisionTest {

    private static RetrievalService service;

    @BeforeAll
    static void setUp() throws Exception {
        RagProperties props = new RagProperties(20, 5, 512, 64, 1.0, 0.5, List.of());

        File md = new File("常见IT术语回答问题.md");
        if (!md.exists()) {
            md = new File("../常见IT术语回答问题.md"); // 兼容从仓库根运行
        }
        byte[] data = Files.readAllBytes(md.toPath());

        Document doc = new Document();
        doc.setId(1L);
        doc.setFileName("常见IT术语回答问题.md");

        List<DocChunk> chunks = new Chunker(props).chunk(doc, DocumentParser.parse(doc.getFileName(), data));
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setId((long) (i + 1));
        }

        Bm25Index index = new Bm25Index();
        index.rebuild(1L, chunks);
        service = new RetrievalService(index, props);
    }

    /** 命中小节标题（首行去掉 # 后）拼接，便于断言 */
    private String titles(String query) {
        RetrievalService.Result r = service.retrieve(List.of(1L), query);
        StringBuilder sb = new StringBuilder();
        for (var p : r.passages()) {
            String first = p.content().split("\n", 2)[0].replaceAll("^#+\\s*", "");
            sb.append("||").append(first);
        }
        return sb.toString();
    }

    @Test
    void definition_hitsOwnSection_only() {
        String t = titles("什么是 Spring Boot");
        assertTrue(t.contains("Spring Boot"), t);
        assertFalse(t.contains("CI/CD"), "不应把正文顺带引用 Spring Boot 的 CI/CD 一节带进来: " + t);
    }

    @Test
    void bean_excludesIncidentalMentionInTransaction() {
        String t = titles("什么是Bean");
        assertTrue(t.contains("Bean"), t);
        assertFalse(t.contains("事务"), "事务一节只是正文提到 Bean，应被标题闸门剔除: " + t);
    }

    @Test
    void rag_definition() {
        assertTrue(titles("什么是RAG").contains("RAG"), titles("什么是RAG"));
    }

    @Test
    void bm25_definition() {
        assertTrue(titles("BM25 是什么").contains("BM25"), titles("BM25 是什么"));
    }

    @Test
    void projectUsage_intent() {
        assertTrue(titles("懒加载在本项目怎么用的").contains("懒加载"), titles("懒加载在本项目怎么用的"));
    }

    @Test
    void faq_intent() {
        String t = titles("为什么不用单独装 Tomcat");
        assertTrue(t.contains("Spring Boot"), t);
    }

    @Test
    void multiTerm_stillPrecise() {
        assertTrue(titles("什么是内网穿透").contains("内网穿透"), titles("什么是内网穿透"));
        assertTrue(titles("Session 和 Cookie 的区别").contains("Session"), titles("Session 和 Cookie 的区别"));
    }
}
