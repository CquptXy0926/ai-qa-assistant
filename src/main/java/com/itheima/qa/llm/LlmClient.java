package com.itheima.qa.llm;

// ↓ Jackson（Spring Boot 默认 JSON 库）三件套：
// JsonNode：JSON 树的通用节点模型（只读解析用）
import com.fasterxml.jackson.databind.JsonNode;
// ObjectMapper：JSON 读写门户，对象↔JSON 字符串互转；线程安全，作为单例 Bean 注入复用
import com.fasterxml.jackson.databind.ObjectMapper;
// ArrayNode / ObjectNode：可写的 JSON 数组/对象构建器（流式 API，put 方法返回自身可链式调用）
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itheima.qa.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;      // 带缓冲的按行读取器
import java.io.InputStreamReader;   // 字节流 → 字符流的桥接（指定 UTF-8 解码）
import java.net.URI;                // URL 的不可变表示
// java.net.http.HttpClient：Java 11+ 官方内置 HTTP 客户端，本项目零额外依赖调 LLM 的关键
import java.net.http.HttpClient;
import java.net.http.HttpRequest;   // 请求构造器（Builder 模式）
import java.net.http.HttpResponse;  // 响应对象
import java.nio.charset.StandardCharsets;  // UTF_8 常量，避免到处写 "UTF-8" 字符串
import java.time.Duration;          // 时间跨度（超时长用）
import java.util.List;
import java.util.function.Consumer; // 函数式接口：接受一个参数、无返回（这里接收流式增量文本）

/**
 * OpenAI 兼容 Chat Completions 客户端：
 * - stream(question, history)：SSE 增量回调真实模型输出；
 * - 未配置 apiKey 时自动进入 Mock 模式（由上层 ChatService 走抽取式回答）。
 * 【讲解要点】"OpenAI 兼容"已成行业标准：DeepSeek、通义、Kimi 等都提供同样的
 * /v1/chat/completions 端点和 SSE 流协议，所以一套客户端代码可切换任意供应商，
 * 只需改 yml 里的 base-url/model/api-key。
 */
@Component
public class LlmClient {

    /** 一轮对话（record）：role 取 "user"/"assistant"，content 为消息文本。
     *  用 record 而不是 Map：类型安全、IDE 可补全、语义自解释 */
    public record Turn(String role, String content) {}

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /**
     * props 由 Spring 注入（启动时已绑定好 yml 配置），mapper 是容器提供的单例 Bean，
     * HttpClient 自己 new：连接超时 10 秒（只限制"建立 TCP 连接"阶段）。
     * HttpClient 线程安全，建一次全程复用。
     */
    public LlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 是否 Mock 模式：委托给配置记录判断（apiKey 为空即 Mock），ChatService 据此分流 */
    public boolean mockMode() {
        return props.mockMode();
    }

    /** 当前模型名（下发给前端 meta 事件展示用，如 "deepseek-chat"） */
    public String modelName() {
        return props.model();
    }

    /**
     * 流式对话：system + 历史轮 + 当前问题，增量内容通过 onDelta 回调。
     * 【讲解要点·SSE 协议】服务端持续输出形如：
     *   data: {"choices":[{"delta":{"content":"你"}}]}
     *   data: {"choices":[{"delta":{"content":"好"}}]}
     *   data: [DONE]
     * 本方法边收边把每个小片段（delta）通过回调交给上层转发给浏览器，实现"打字机"效果。
     * @param systemPrompt 系统提示词（角色设定 + 参考资料）
     * @param history      最近若干轮历史消息，让模型有上下文记忆
     * @param question     当前用户问题
     * @param onDelta      增量回调：每收到一小段文本就调用一次
     * @return 完整答案文本（同时被累加在内部 StringBuilder 里返回，供落库）
     */
    public String stream(String systemPrompt, List<Turn> history, String question, Consumer<String> onDelta)
            throws Exception {
        // ---- 1) 手工组装 OpenAI 协议请求体 ----
        // ObjectNode 是"可写的 JSON 对象"，put() 系列方法链式添加字段
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());     // 用哪个模型
        body.put("stream", true);             // 关键：true=要求 SSE 流式返回
        if (props.temperature() != null) {    // 可选参数：没配就不传，让服务端用默认值
            body.put("temperature", props.temperature());
        }
        if (props.maxTokens() != null) {
            body.put("max_tokens", props.maxTokens());
        }
        // messages 数组是协议核心：按顺序的对话上下文
        ArrayNode messages = body.putArray("messages");
        // 链式调用：addObject() 往数组加一个 {}，再连续 put 填 role 和 content
        messages.addObject().put("role", "system").put("content", systemPrompt); // 系统人设
        for (Turn t : history) {  // 历史轮原样带入（user/assistant 交替）
            messages.addObject().put("role", t.role()).put("content", t.content());
        }
        messages.addObject().put("role", "user").put("content", question);        // 当前问题压轴

        // ---- 2) 构造并发送 HTTP 请求（同步模式：当前线程阻塞直到拿到响应头）----
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimUrl(props.baseUrl()) + "/chat/completions")) // 拼出完整端点
                .timeout(Duration.ofMinutes(3))   // 整个请求（含收流）的总超时：3 分钟
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())  // OpenAI 鉴权头标准格式
                // BodyPublishers.ofString：把 JSON 字符串作为请求体，明确指定 UTF-8（中文不乱码）
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        // BodyHandlers.ofInputStream()：响应体以字节流形式返回 —— 适合逐行读 SSE，不必等全部下载完
        HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) { // 非 200（如 401 key 错、429 限流）：读全部错误体抛出
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("LLM 接口返回 " + resp.statusCode() + ": " + truncate(err));
        }
        // ---- 3) 逐行解析 SSE 流 ----
        StringBuilder full = new StringBuilder(); // 累加完整答案（落库用）；
                                                  // 【讲解要点】循环拼字符串永远用 StringBuilder，别用 +
        // try-with-resources：reader 及其底层流会在代码块结束/异常时自动 close，杜绝泄漏
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            // 经典 IO 惯用法：readLine() 读到流末尾返回 null，赋值+判断一行搞定
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) { // SSE 还有 event:/id:/注释行，只关心 data: 数据行
                    continue;
                }
                String payload = line.substring(5).trim(); // 去掉 "data:" 前缀（5 个字符）并去空白
                if ("[DONE]".equals(payload)) {  // OpenAI 协议约定的流结束哨兵
                    break;
                }
                JsonNode node = mapper.readTree(payload); // 该事件 JSON → 树模型
                // path()：安全取值链，路径上任何一级不存在都返回"MissingNode"而非抛 NPE，
                // 等价于 choices[0].delta.content 的深层取值
                JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull()) { // 确认真的取到了内容
                    String piece = delta.asText();               // 转 Java 字符串
                    if (!piece.isEmpty()) {
                        full.append(piece);      // ① 累积到完整答案
                        onDelta.accept(piece);   // ② 回调通知上层（ChatService 借此实时推给浏览器）
                    }
                }
            }
        }
        return full.toString();
    }

    /**
     * URL 规整：去掉配置值尾部斜杠，防止拼接出 "//chat/completions"；
     * 完全没配则回退到 OpenAI 官方默认地址。
     */
    private static String trimUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String u = baseUrl.trim();
        // 条件表达式：以 "/" 结尾就砍掉最后一个字符，否则原样返回
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** 错误信息截断到 500 字符：防止异常体过大污染日志/响应 */
    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
