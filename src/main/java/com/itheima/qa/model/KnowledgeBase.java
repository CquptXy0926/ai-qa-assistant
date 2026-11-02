package com.itheima.qa.model;

import jakarta.persistence.*;

/**
 * 知识库（制度 / 产品手册 / FAQ / 自定义）。
 * 是文档的顶层容器，也是检索时"限定范围"的单位（检索接口传 kbIds 列表）。
 */
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64) // 库名唯一，便于"按名查是否存在"的幂等初始化
    private String name;

    @Column(length = 255)      // 描述，可空
    private String description;

    /** 默认可见范围：ALL=全员可见，ADMIN_ONLY=仅管理员（敏感库）。
     *  【讲解要点】这里用字符串而不是枚举/外键，是把"简单枚举值"直接落在列上，
     *  读取处（KbController/ChatService）用 "ADMIN_ONLY".equals(...) 判断即可，够用且免迁移 */
    @Column(nullable = false, length = 16)
    private String visibility = "ALL";   // 字段初始值：新建对象默认可见全员

    public KnowledgeBase() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
}
