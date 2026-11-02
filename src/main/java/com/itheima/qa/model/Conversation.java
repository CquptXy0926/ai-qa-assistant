package com.itheima.qa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 会话：归属某个用户，包含多轮问答消息。
 * 【讲解要点】没有用 @ManyToOne 关联 User，只存 userId 长整型 ——
 * 轻量做法：列表页只需按用户查会话（findByUserId），不需要联查用户对象，省一次 JOIN。
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;      // 归属用户 id（数据隔离依据：查历史只能查自己的）

    @Column(nullable = false, length = 128)
    private String title;     // 会话标题：默认"新会话"，首次提问后自动截前 20 字当标题

    /** 本次会话绑定的知识库范围，逗号分隔的 kbId；空=全部可见知识库。
     *  【讲解要点】"冗余字符串存外键集合"（如 "1,3,5"）是不建中间表的最简方案；
     *  代价是不能按 kbId 高效反查会话 —— 本场景只需"会话→范围"单向读取，够用了 */
    @Column(length = 255)
    private String kbIds;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKbIds() { return kbIds; }
    public void setKbIds(String kbIds) { this.kbIds = kbIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
