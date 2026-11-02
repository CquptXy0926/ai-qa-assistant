package com.itheima.qa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文档：上传后进入解析流水线，状态机 PENDING → PROCESSING → READY / FAILED。
 * 同名文档重新上传生成新版本（version+1），旧版本自动停用。
 * 【讲解要点·状态机】用一个受限枚举表达文档生命周期，前端据此轮询显示进度条；
 * 【讲解要点·版本策略】不做"覆盖更新"而是"新版生效+旧版停用"，旧数据保留可回滚——
 * 这是企业知识库常见的安全做法。
 */
@Entity
@Table(name = "document")
public class Document {

    /** 嵌套枚举（相当于类内的"常量集合"），表示解析状态机的四个状态 */
    public enum Status { PENDING, PROCESSING, READY, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 多对一：很多文档属于一个知识库
    @JoinColumn(name = "kb_id")                           // 外键列名：本表存 knowledge_base 的 id
    private KnowledgeBase knowledgeBase;                  // LAZY=懒加载：查文档时不立即联查知识库，
                                                          // 访问 getKnowledgeBase() 时才发 SQL（性能优化核心）

    @Column(nullable = false, length = 255)
    private String fileName;      // 展示用文件名（同名不同版本靠 version 区分）

    /** 原始文件在本地存储目录中的相对路径（见 FileStorageService），磁盘与数据库各存一份信息 */
    @Column(nullable = false, length = 512)
    private String storagePath;

    /** 文件类型：pdf/docx/xlsx/pptx/md/txt/html；由上传时从文件名后缀截取 */
    @Column(nullable = false, length = 16)
    private String fileType;

    @Column(nullable = false)
    private int version = 1;   // 版本号：同名重传时 +1（业务逻辑在 IngestService.upload）

    @Enumerated(EnumType.STRING)  // 【讲解要点】枚举入库方式：STRING 存名字"READY"（可读、可安全改枚举顺序）；
                                  // 默认的 ORDINAL 存序号，一旦枚举重排数据全错 —— 生产必须用 STRING
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;   // 新上传默认"排队待处理"

    /** 解析失败原因（FAILED 时供管理员排查，前端直接展示） */
    @Column(length = 512)
    private String errorMsg;

    /** 是否启用（停用后不参与检索，但保留数据）—— 逻辑删除/灰度下线的开关 */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();  // 字段初始化 = 插入时刻的创建时间

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * @PreUpdate：JPA 生命周期回调 —— Hibernate 每次 UPDATE 该实体前自动调用此方法。
     * 【讲解要点】不用在每个 Service 里手动 setUpdatedAt，ORM 帮你在持久化瞬间统一维护。
     */
    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
    public void setKnowledgeBase(KnowledgeBase kb) { this.knowledgeBase = kb; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
