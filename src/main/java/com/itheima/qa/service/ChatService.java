package com.itheima.qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.qa.config.RagProperties;
import com.itheima.qa.llm.LlmClient;
import com.itheima.qa.model.Conversation;
import com.itheima.qa.model.KnowledgeBase;
import com.itheima.qa.model.Message;
import com.itheima.qa.model.User;
import com.itheima.qa.rag.RetrievalService;
import com.itheima.qa.repo.ConversationRepository;
import com.itheima.qa.repo.KbRepository;
import com.itheima.qa.repo.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 问答编排：敏感词检查 → BM25 检索 → 置信度兜底 → LLM 流式生成（或 Mock 抽取式回答）
 * → 消息与引用落库。前端通过 SSE 事件接收：
 *   event: citations / delta / meta / error / done
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 敏感词拦截（V1.0 内置最小词表，后续可外置为配置/词库管理页） */
    private static final List<String> SENSITIVE_WORDS = List.of("政治敏感", "违法乱纪");

    private static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手。请严格遵守：
            1. 只能依据下面给出的【参考资料】回答，禁止编造资料以外的内容；
            2. 引用资料内容时，在句末用方括号标注编号，如 [1]、[2]，可同时引用多个如 [1][3]；
            3. 如果参考资料不足以回答问题，直接回复：知识库中未找到相关内容，建议联系管理员补充资料。
            4. 回答使用简洁的中文 Markdown 格式。
            """;

    private final RetrievalService retrieval;
    private final LlmClient llm;
    private final ConversationRepository convRepository;
    private final MessageRepository msgRepository;
    private final KbRepository kbRepository;
    private final RagProperties ragProps;
    private final ObjectMapper mapper;
    private final Executor chatExecutor;
    private final TransactionTemplate txTemplate;

    public ChatService(RetrievalService retrieval, LlmClient llm,
                       ConversationRepository convRepository, MessageRepository msgRepository,
                       KbRepository kbRepository, RagProperties ragProps,
                       ObjectMapper mapper, Executor ingestExecutor, TransactionTemplate txTemplate) {
        this.retrieval = retrieval;
        this.llm = llm;
        this.convRepository = convRepository;
        this.msgRepository = msgRepository;
        this.kbRepository = kbRepository;
        this.ragProps = ragProps;
        this.mapper = mapper;
        this.chatExecutor = ingestExecutor;
        this.txTemplate = txTemplate;
    }

    /** 解析提问范围：会话绑定优先，否则取该用户可见的全部知识库 */
    public List<Long> resolveKbIds(User user, Conversation conv) {
        if (conv != null && conv.getKbIds() != null && !conv.getKbIds().isBlank()) {
            return Arrays.stream(conv.getKbIds().split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        }
        return kbRepository.findAll().stream()
                .filter(kb -> user.isAdmin() || !"ADMIN_ONLY".equals(kb.getVisibility()))
                .map(KnowledgeBase::getId).toList();
    }

    @Transactional
    public Conversation ensureConversation(User user, Long conversationId, List<Long> kbIds) {
        Conversation conv;
        if (conversationId != null) {
            conv = convRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
            if (!conv.getUserId().equals(user.getId())) {
                throw new IllegalArgumentException("无权访问该会话");
            }
        } else {
            conv = new Conversation();
            conv.setUserId(user.getId());
            conv.setTitle("新会话");
            if (kbIds != null && !kbIds.isEmpty()) {
                conv.setKbIds(joinIds(kbIds));
            }
            convRepository.save(conv);
        }
        return conv;
    }

    public static String joinIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    /** 发起问答，返回 SSE 流 */
    public SseEmitter chat(User user, Long conversationId, List<Long> kbIds, String question) {
        Conversation conv = ensureConversation(user, conversationId, kbIds);
        SseEmitter emitter = new SseEmitter(180_000L);

        chatExecutor.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                // 1) 敏感词拦截
                if (hitSensitive(question)) {
                    send(emitter, "delta", Map.of("text", "该问题涉及敏感内容，无法回答。"));
                    persistFinish(emitter, conv.getId(), question, "该问题涉及敏感内容，无法回答。", null, false, t0);
                    return;
                }
                // 2) 检索
                List<Long> scope = resolveKbIds(user, conv);
                RetrievalService.Result ret = retrieval.retrieve(scope, question);

                // 3) 未命中 → 兜底话术
                if (!ret.hit()) {
                    String fallback = "知识库中未找到相关内容，建议换个问法，或联系管理员确认已上传相关制度/手册。";
                    send(emitter, "citations", List.of());
                    send(emitter, "delta", Map.of("text", fallback));
                    persistFinish(emitter, conv.getId(), question, fallback, null, false, t0);
                    return;
                }

                // 4) 引用元信息先下发（前端渲染引用卡片）
                List<Map<String, Object>> citationList = ret.passages().stream()
                        .map(p -> Map.<String, Object>of(
                                "refNo", p.refNo(), "docId", p.docId(), "chunkId", p.chunkId(),
                                "fileName", p.fileName(), "location", p.location(),
                                "snippet", p.content().length() > 160 ? p.content().substring(0, 160) + "..." : p.content(),
                                "score", p.score()))
                        .toList();
                send(emitter, "citations", citationList);

                // 5) 生成答案：真实 LLM 流式 或 Mock 抽取式
                String answer;
                if (llm.mockMode()) {
                    answer = mockAnswer(ret.passages(), question, emitter);
                } else {
                    String system = SYSTEM_PROMPT + "\n【参考资料】\n" + buildContext(ret.passages());
                    List<LlmClient.Turn> history = loadHistory(conv.getId());
                    answer = llm.stream(system, history, question, delta -> {
                        try {
                            send(emitter, "delta", Map.of("text", delta));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    if (answer == null || answer.isBlank()) {
                        answer = "模型未返回内容，请稍后重试。";
                        send(emitter, "delta", Map.of("text", answer));
                    }
                }

                // 6) 落库 + 下发 meta
                persistFinish(emitter, conv.getId(), question, answer, citationList, true, t0);
            } catch (Exception e) {
                log.warn("问答处理失败: {}", e.toString());
                try {
                    send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                    emitter.complete();
                } catch (Exception ignore) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /**
     * Mock 模式（未配 LLM Key）：意图感知抽取，不接 LLM 也能给出简洁准确的答案。
     * 【核心思路】
     * 1. 识别问题意图："什么是 X"→定义、"X 在本项目"→实现、"为什么 X"→FAQ；
     * 2. 把每个 chunk 解析为 {标题, 标签→内容} 结构；
     * 3. 按意图只返回最匹配的那条 bullet，不整节倒出；
     * 4. 只取 top 2 个最相关片段，避免信息过载；
     * 5. 非结构化文档（PDF/txt）退回句子级抽取兑底。
     */
    private String mockAnswer(List<RetrievalService.Passage> passages, String question, SseEmitter emitter)
            throws Exception {
        Intent intent = detectIntent(question);
        // 只取前 2 个最相关片段，避免回答过长
        int limit = Math.min(2, passages.size());
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        boolean hasStructured = false;

        for (int i = 0; i < limit; i++) {
            RetrievalService.Passage p = passages.get(i);
            ParsedSection parsed = parseSection(p.content());
            if (parsed != null && !parsed.bullets().isEmpty()) {
                hasStructured = true;
                String titleKey = parsed.title() == null ? "" : parsed.title();
                if (!seen.add(titleKey)) {
                    continue;
                }
                // 按意图匹配 bullet
                String answer = matchIntent(parsed, intent);
                if (answer != null && !answer.isBlank()) {
                    if (parsed.title() != null && !parsed.title().isBlank()) {
                        sb.append("▶ ").append(parsed.title()).append('\n');
                    }
                    sb.append(answer).append(" [").append(p.refNo()).append("]\n\n");
                } else {
                    // 意图未匹配到特定标签，输出全部 bullet（但只输出前 2 条）
                    if (parsed.title() != null && !parsed.title().isBlank()) {
                        sb.append("▶ ").append(parsed.title()).append('\n');
                    }
                    int cnt = 0;
                    for (var entry : parsed.bullets().entrySet()) {
                        if (cnt++ >= 2) break;
                        sb.append(entry.getValue()).append('\n');
                    }
                    sb.append("[").append(p.refNo()).append("]\n\n");
                }
            }
        }

        // 非结构化文档（PDF/txt）或解析失败：退回句子级抽取兑底
        if (!hasStructured) {
            for (int i = 0; i < limit; i++) {
                RetrievalService.Passage p = passages.get(i);
                String extract = extractRelevant(p.content(), question, 2);
                if (!extract.isBlank() && seen.add(extract)) {
                    sb.append(extract).append(" [").append(p.refNo()).append("]\n\n");
                }
            }
        }

        String answer = sb.toString().trim();
        if (answer.isEmpty()) {
            answer = "知识库中未找到与该问题直接相关的内容，建议换个问法或联系管理员补充资料。";
        }
        // 伪流式下发
        for (int i = 0; i < answer.length(); i += 24) {
            send(emitter, "delta", Map.of("text", answer.substring(i, Math.min(i + 24, answer.length()))));
            Thread.sleep(20);
        }
        return answer;
    }

    // ==================== 意图感知抽取内部模型 ====================

    /** 问题意图分类 */
    enum Intent { DEFINITION, PROJECT_USAGE, FAQ, GENERAL }

    /** 解析后的章节：标题 + 有序标签→内容映射 */
    record ParsedSection(String title, java.util.LinkedHashMap<String, String> bullets) {}

    /**
     * 意图检测：根据问题关键词判断用户想要什么类型的信息。
     * - "什么是 X" / "X 是什么" / "介绍 X" → DEFINITION（要一句话定义）
     * - "X 在本项目" / "X 在哪" / "X 怎么实现" → PROJECT_USAGE（要项目实现细节）
     * - "为什么 X" / "X 的原因" → FAQ（要常见问题解答）
     * - 其他 → GENERAL（返回全部内容）
     */
    static Intent detectIntent(String question) {
        String q = question.toLowerCase().trim();
        if (q.contains("什么是") || q.contains("是什么") || q.contains("的定义")
                || q.contains("介绍") || q.contains("概念") || q.contains("含义")
                || q.contains("意思") || q.contains("定义")) {
            return Intent.DEFINITION;
        }
        if (q.contains("本项目") || q.contains("在哪") || q.contains("怎么实现")
                || q.contains("怎么用") || q.contains("如何使用") || q.contains("代码")
                || q.contains("实现") || q.contains("哪里用") || q.contains("怎么配")) {
            return Intent.PROJECT_USAGE;
        }
        if (q.contains("为什么") || q.contains("原因") || q.contains("常问")
                || q.contains("常见问题") || q.contains("干嘛") || q.contains("干什么")) {
            return Intent.FAQ;
        }
        return Intent.GENERAL;
    }

    /**
     * 把一个 chunk 解析为 ParsedSection：
     * - 首行 ### 标题 → title
     * - 后续 "- **标签**：内容" → bullets.put(标签, 内容)
     * - 如果不含任何 "- **" 结构，返回 null（表示非结构化，走句子抽取兑底）
     */
    static ParsedSection parseSection(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\n");
        String title = null;
        java.util.LinkedHashMap<String, String> bullets = new java.util.LinkedHashMap<>();
        boolean hasLabeledBullet = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 标题行
            if (line.startsWith("#") && title == null) {
                title = line.replaceAll("^#+\\s*", "").trim();
                continue;
            }
            // 带标签的 bullet："- **一句话**：xxx" 或 "- **本项目**：xxx"
            java.util.regex.Matcher bm = LABELED_BULLET.matcher(line);
            if (bm.matches()) {
                hasLabeledBullet = true;
                String label = bm.group(1).trim();
                String body = cleanMd(bm.group(2).trim());
                bullets.put(label, body);
                continue;
            }
            // 普通 bullet（无标签）
            if (line.startsWith("-")) {
                String body = cleanMd(line.replaceAll("^-+\\s*", "").trim());
                if (!body.isEmpty()) {
                    bullets.put("_" + bullets.size(), body);
                }
            }
        }
        // 没有任何 "- **标签**" 结构 → 不是本方案的 md 章节，走句子抽取
        if (!hasLabeledBullet) {
            return null;
        }
        return new ParsedSection(title, bullets);
    }

    /** 匹配 "- **标签**：内容" 或 "- **标签**: 内容" */
    private static final java.util.regex.Pattern LABELED_BULLET =
            java.util.regex.Pattern.compile("^-+\\s*\\*\\*(.+?)\\*\\*[:：]?\\s*(.+)");

    /** 清理 markdown 视觉噪音：**加粗**、`代码` */
    private static String cleanMd(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`(.+?)`", "$1")
                .trim();
    }

    /**
     * 按意图匹配最佳 bullet：
     * - DEFINITION → 找 "一句话" 标签
     * - PROJECT_USAGE → 找 "本项目" 标签
     * - FAQ → 找 "常问" 标签
     * - 找不到则返回 null（上层会 fallback 输出全部）
     */
    static String matchIntent(ParsedSection parsed, Intent intent) {
        return switch (intent) {
            case DEFINITION -> findBullet(parsed, "一句话", "定义", "解释", "简介");
            case PROJECT_USAGE -> findBullet(parsed, "本项目", "实现", "用法", "应用");
            case FAQ -> findBullet(parsed, "常问", "常见问题", "FAQ");
            case GENERAL -> null;
        };
    }

    /** 在 bullets 里按优先级找第一个匹配关键词的条目 */
    private static String findBullet(ParsedSection parsed, String... keywords) {
        for (String kw : keywords) {
            for (var entry : parsed.bullets().entrySet()) {
                if (entry.getKey().contains(kw)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** 按字符 2-gram 重合度给句子打分：重叠词元数降序、长度升序 tie-break，取前 n 句 */
    static String extractRelevant(String content, String question, int n) {
        java.util.Set<String> qTokens = new java.util.HashSet<>(com.itheima.qa.rag.Tokenizer.tokenize(question));
        String[] sentences = content.split("(?<=[。！？；\\n])");
        record Scored(String s, long overlap, int len) {}
        List<Scored> scored = new ArrayList<>();
        for (String s : sentences) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            java.util.Set<String> st = new java.util.HashSet<>(com.itheima.qa.rag.Tokenizer.tokenize(t));
            long overlap = st.stream().filter(qTokens::contains).count();
            if (overlap > 0) {
                scored.add(new Scored(t, overlap, t.length()));
            }
        }
        scored.sort((a, b) -> a.overlap() != b.overlap()
                ? Long.compare(b.overlap(), a.overlap())
                : Integer.compare(a.len(), b.len()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(n, scored.size()); i++) {
            out.append(scored.get(i).s()).append(' ');
        }
        return out.toString().trim();
    }

    private String buildContext(List<RetrievalService.Passage> passages) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalService.Passage p : passages) {
            sb.append('[').append(p.refNo()).append("] 《").append(p.fileName())
                    .append("》（").append(p.location()).append("）\n")
                    .append(p.content()).append("\n\n");
        }
        return sb.toString();
    }

    /** 最近 10 轮历史（不含当前问题） */
    private List<LlmClient.Turn> loadHistory(Long convId) {
        List<Message> msgs = msgRepository.findByConversationIdOrderByIdAsc(convId);
        int from = Math.max(0, msgs.size() - ragProps.finalK() * 4); // 约 10 轮
        return msgs.subList(from, msgs.size()).stream()
                .map(m -> new LlmClient.Turn(m.getRole(), m.getContent()))
                .toList();
    }

    private boolean hitSensitive(String q) {
        return SENSITIVE_WORDS.stream().anyMatch(q::contains);
    }

    /** 落库（独立事务）+ SSE 收尾；后台线程自调用不走代理，故用 TransactionTemplate */
    private void persistFinish(SseEmitter emitter, Long convId, String question, String answer,
                       List<Map<String, Object>> citations, boolean hit, long t0) throws Exception {
        Long msgId = txTemplate.execute(status -> {
            Message q = new Message();
            q.setConversationId(convId);
            q.setRole("user");
            q.setContent(question);
            msgRepository.save(q);

            Message a = new Message();
            a.setConversationId(convId);
            a.setRole("assistant");
            a.setContent(answer);
            try {
                a.setCitations(citations == null ? null : mapper.writeValueAsString(citations));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            a.setHit(hit);
            a.setLatencyMs(System.currentTimeMillis() - t0);
            msgRepository.save(a);

            // 首问自动改标题
            Conversation conv = convRepository.findById(convId).orElseThrow();
            if ("新会话".equals(conv.getTitle())) {
                conv.setTitle(question.length() > 20 ? question.substring(0, 20) : question);
                convRepository.save(conv);
            }
            return a.getId();
        });

        send(emitter, "meta", Map.of("messageId", msgId, "hit", hit,
                "latencyMs", System.currentTimeMillis() - t0, "model", llm.mockMode() ? "mock" : llm.modelName()));
        send(emitter, "done", Map.of());
        emitter.complete();
    }

    private void send(SseEmitter emitter, String event, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
    }
}
