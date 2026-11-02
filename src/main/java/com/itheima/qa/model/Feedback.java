package com.itheima.qa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 答案反馈：1=有用，-1=没用（点踩原因多选，逗号分隔）。
 * 【讲解要点】messageId 上建 unique 约束 —— 一条消息只能有一条反馈，
 * 数据库层面兜底"重复点赞"，业务层（ChatController）再做"覆盖更新"。
 */
@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true) // 一条消息最多一条反馈（唯一约束防重复）
    private Long messageId;

    @Column(nullable = false)
    private int rating;      // 1=有用（赞）；-1=没用（踩）；0 表示未评价（查询时用 orElse(0) 兜底）

    @Column(length = 255)
    private String reasons;  // 点踩原因多选，逗号拼接，如 "答非所问,内容过时"

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Feedback() {}     // JPA 无参构造

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public String getReasons() { return reasons; }
    public void setReasons(String reasons) { this.reasons = reasons; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
