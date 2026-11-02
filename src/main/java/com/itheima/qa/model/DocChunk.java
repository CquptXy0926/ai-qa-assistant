package com.itheima.qa.model;

import jakarta.persistence.*;

/**
 * 文档切片：检索与引用溯源的最小单元。
 * 【讲解要点】一篇长文档被 Chunker 切成 N 条 DocChunk 存库；
 * BM25 索引的每个条目就指向一条 chunk —— "命中切片 → 定位文档+页码" 的引用链路全靠它。
 */
@Entity
@Table(name = "doc_chunk")
public class DocChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 多条切片属于同一篇文档
    @JoinColumn(name = "doc_id")                          // 外键列：所属文档 id
    private Document document;

    /** 切片在文档内的序号，同时作为引用位置的标识。
     *  【讲解要点】这里没用数据库自增 id 当"第几片"，因为 reindex 重建后 id 会变，
     *  而 chunkIndex 是稳定的业务序号（0,1,2...），引用展示更可靠 */
    @Column(nullable = false)
    private int chunkIndex;

    /** 位置描述：页码 / 行号区间，如 "第3页" / "行1-45"，由解析器 DocumentParser 生成 */
    @Column(length = 64)
    private String location;

    @Lob                       // Large Object：映射为大文本类型（H2 里是 CLOB），可存超长内容
    @Column(nullable = false)
    private String content;    // 切片原文 —— 检索命中后既进 prompt 也给用户看

    public DocChunk() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
