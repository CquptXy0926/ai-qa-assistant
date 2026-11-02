package com.itheima.qa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 消息：role = user / assistant。
 * assistant 消息带引用来源 JSON 与是否命中知识库标记（用于运营统计）。
 * 【讲解要点】这是"非结构化数据存 JSON 列"的典型工程折中：
 * citations 是数组结构，为其建表过头，直接序列化成 JSON 存 @Lob，读时反序列化给前端。
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;  // 所属会话 id（同样走轻量外键，不建对象关联）

    @Column(nullable = false, length = 16)
    private String role;          // "user"（提问）或 "assistant"（回答），与 LLM 协议的 role 字段对齐

    @Lob
    @Column(nullable = false)
    private String content;       // 消息正文（问题原文或完整答案文本）

    /** 引用来源 JSON 数组：[{docId,fileName,location,snippet,score}] */
    @Lob
    private String citations;     // 用户消息为 null；assistant 消息存序列化后的引用列表

    /** assistant 消息：是否命中知识库（false=走了兜底话术）。
     *  【讲解要点】用包装类型 Boolean 而非 boolean：可以表达三种状态
     *  true=命中 / false=没命中 / null=不适用（user 消息根本没有"命中率"概念），
     *  看板统计命中率时只筛 role='assistant' 的记录 */
    private Boolean hit;

    /** 问答耗时（毫秒）：从收到问题到答案生成完，用于性能监控 */
    private Long latencyMs;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Message() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCitations() { return citations; }
    public void setCitations(String citations) { this.citations = citations; }
    public Boolean getHit() { return hit; }
    public void setHit(Boolean hit) { this.hit = hit; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
