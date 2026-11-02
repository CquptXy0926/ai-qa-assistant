package com.itheima.qa.config;

// 同上：绑定 application.yml 中 app.llm.* 配置（base-url → baseUrl，宽松绑定）
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置（OpenAI 兼容 API）。apiKey 为空时系统自动进入 Mock 模式。
 * 【讲解要点】这里用包装类型 Double/Integer 而不是 double/int：
 * null 表示"用户没配这项"，此时 LlmClient 就不往请求体里写该字段，
 * 让模型服务端使用它自己的默认值 —— 这是"可选配置"的常见建模手法。
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        String baseUrl,      // API 服务器地址，如 https://api.deepseek.com（OpenAI 兼容协议的基地址）
        String apiKey,       // 密钥；为空串/null ⇒ mockMode() 返回 true，走本地抽取式回答，不花一分钱
        String model,        // 模型名，如 deepseek-chat，会写进请求体的 "model" 字段
        Double temperature,  // 采样温度（0~2）：越小回答越稳定保守，问答场景用 0.2 降低胡说概率
        Integer maxTokens    // 单次回答的 token 上限，防止超长输出失控
) {

    /**
     * 是否处于 Mock（伪 LLM）模式。
     * isBlank()：Java 11+ 的字符串方法，null 以外的"空白串"（含空格/Tab）也返回 true，
     * 比 == null || equals("") 更严谨。
     */
    public boolean mockMode() {
        return apiKey == null || apiKey.isBlank();
    }
}
