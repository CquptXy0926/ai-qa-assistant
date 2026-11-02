# AI 智能问答助手（ai-qa-assistant）

基于 RAG（检索增强生成）的企业知识库问答系统，对应 PRD：[`docs/PRD-AI智能问答助手.md`](../docs/PRD-AI智能问答助手.md)。

管理员上传制度 / 手册 / FAQ 等文档，系统自动解析、切分、建立 BM25 索引；
普通用户通过网页以自然语言提问，答案流式输出并附带知识库原文引用；
未配置 LLM Key 时自动降级为**纯检索抽取式回答（Mock 模式）**，开箱即用。

## 快速开始

```bash
cd ai-qa-assistant
mvn spring-boot:run          # 或：mvn package -DskipTests && java -jar target/ai-qa-assistant-1.0.0.jar
```

浏览器打开 <http://localhost:8080>：

| 角色 | 账号 | 密码 | 能力 |
|------|------|------|------|
| 管理员 | `admin` | `admin123` | 问答 + 知识库/文档管理 + 运营看板 |
| 普通用户 | `user` | `user123` | 问答 + 反馈（也可在登录页注册新账号） |

> 数据保存在 `ai-qa-assistant/data/`（H2 文件库 + 上传的原始文件），删除该目录即可重置。

## 配置真实 LLM（可选）

默认 `api-key` 为空 → Mock 模式（检索 + 抽取式回答，不调任何外部服务）。
接入任意 **OpenAI 兼容** API（DeepSeek / 通义 / Kimi / 自建 vLLM 等），编辑
[`src/main/resources/application.yml`](src/main/resources/application.yml)：

```yaml
app:
  llm:
    base-url: https://api.deepseek.com   # OpenAI 兼容网关地址
    api-key: "sk-xxxxxxxx"               # 填了即切换真实 LLM 流式回答
    model: deepseek-chat
    temperature: 0.2
    max-tokens: 2048
```

也可用环境变量覆盖：`APP_LLM_API_KEY=sk-xxx java -jar target/ai-qa-assistant-1.0.0.jar`

## 功能一览

- **登录鉴权**：Session + SHA-256 加盐密码；`ADMIN` / `USER` 两种角色，接口层拦截（非管理员访问管理接口返回 401/403）
- **知识库管理**：创建/改名/删除知识库（默认内置：公司制度、产品手册、FAQ）；支持可见性 `PUBLIC` / `ADMIN_ONLY`
- **文档摄取**：上传 `pdf / docx / xlsx / pptx / txt / md / html / csv`（≤50MB）→ 异步解析 → 句子边界切分（512 字/片，重叠 64）→ BM25 索引；状态机 `PENDING → PROCESSING → READY/FAILED`；同名重传自动 **版本 +1**、旧版停用；扫描版 PDF 给出明确报错；重启后自动重建索引 / 恢复中断文档
- **检索问答**：多知识库联合召回（Top-20 → 单文档最多 2 片 → Final-5）；**双重置信度兜底**（查询词覆盖率为主、BM25 分数为辅，未命中返回引导话术而非瞎编）；敏感词前置拦截；SSE 流式输出
- **引用对齐**：答案标注 `[n]` 对应参考来源卡片，点击弹窗查看片段原文及位置（第 N 页 / 行 a-b / Slide N）
- **反馈闭环**：每条答案 👍/👎（👎 需选原因），沉淀 BadCase
- **运营看板**：近 7 日提问量、命中率、采纳率、每日柱状图、BadCase 明细

## 架构说明

```
浏览器（static/ 原生 HTML+JS，无构建工具）
   │  fetch + SSE 解析
   ▼
web/ Controller ── AuthInterceptor（登录态 + ADMIN 角色）
   │
   ├─ service/ChatService ──► rag/RetrievalService ──► rag/Bm25Index（内存，按知识库分桶）
   │        │                       ▲
   │        ▼                        │ chunk 召回
   │   llm/LlmClient（OpenAI 兼容 SSE 流式；无 Key → Mock 抽取式回答）
   │
   ├─ service/IngestService ──► rag/DocumentParser（PDFBox/POI）──► rag/Chunker
   │        │（线程池异步，独立事务 Bean IngestTx 落库并重建索引）
   ▼        ▼
model/ 实体 + repo/ Spring Data JPA ──► H2 文件库（./data/qa）；原始文件 ──► ./data/files
```

关键设计取舍：

- **零外部依赖**：H2 文件库替代 MySQL、内存 BM25 替代 ES/向量库、原生静态页替代前端框架——单 `jar` 即整个产品，适合内网快速部署
- **中文检索**：`Tokenizer` 对中文取 1-gram + 2-gram、英文取小写单词，配合 BM25（k1=1.2, b=0.75），小语料即可用
- **置信度兜底**：小语料下 BM25 绝对分会失真，故以「查询词元在 top1 片段的覆盖率 ≥0.5」为主判据，覆盖率 0.3~0.5 时需分数同时达标，否则走兜底话术（见 `rag/RetrievalService`）
- **事务边界**：异步摄取与启动重建均拆到独立 Bean（`IngestTx`/`StartupIndexer`），规避 `@Transactional` 自调用失效；上传事务与异步任务的竞态用重试等待解决

## API 摘要

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/auth/login` `/register` `/logout` | 登录/注册/登出 | 公开（注册为普通用户） |
| POST | `/api/chat` | 提问，SSE 流式返回 `citations/delta/meta/done` | 登录 |
| GET/POST/DELETE | `/api/conversations` | 会话列表/新建/删除 | 登录（限本人） |
| GET | `/api/conversations/{id}/messages` | 历史消息 | 登录（限本人） |
| POST | `/api/messages/{id}/feedback` | 👍/👎 反馈 | 登录（限本人会话） |
| GET | `/api/kb` | 知识库列表（USER 只见 PUBLIC） | 登录 |
| POST/PUT/DELETE | `/api/kb` `/{id}` | 知识库增改删 | ADMIN |
| POST | `/api/documents/upload?kbId=` | 上传文档（multipart） | ADMIN |
| GET | `/api/documents?kbId=` | 文档列表（含状态/版本） | ADMIN |
| POST | `/api/documents/{id}/toggle` `/reindex` | 启停 / 重新解析 | ADMIN |
| DELETE | `/api/documents/{id}` | 删除文档及索引 | ADMIN |
| GET | `/api/chunks/{id}` | 查看引用片段原文 | 登录 |
| GET | `/api/admin/dashboard` | 运营看板数据 | ADMIN |

## 目录结构

```
ai-qa-assistant/
├── pom.xml                     # Spring Boot 3.5.4 / Java 21
├── data/                       # 运行时生成：H2 库 + 上传文件（.gitignore）
└── src/main/
    ├── resources/
    │   ├── application.yml     # 端口、RAG、LLM 配置
    │   └── static/             # 前端：login.html / index.html / css / js
    └── java/com/itheima/qa/
        ├── config/             # 属性绑定、拦截器、初始数据、Bean
        ├── model/  repo/       # 7 张表实体 + Spring Data 仓库
        ├── rag/                # 分词、BM25、解析、切分、检索
        ├── llm/                # OpenAI 兼容流式客户端
        ├── service/            # 摄取流水线、问答编排、用户、存储
        └── web/                # REST 控制器 + 全局异常处理
```

## 已知边界

- 内存 BM25 适合万级切片；更大语料建议换专业向量库/ES（接口已按 `RetrievalService` 收口，可平滑替换）
- 未实现向量语义召回（纯词法），同义改写类问题依赖 LLM 模式补强
- 单实例部署（H2 文件锁 + 内存索引），多实例需外接 MySQL + 共享索引
