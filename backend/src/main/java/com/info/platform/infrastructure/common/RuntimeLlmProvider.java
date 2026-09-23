package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code llm.provider.{name}} 键的类型化只读视图（T34，供 T35 消费点用时读取）。
 *
 * <p>由 {@code ConfigCenter#provider(String)} 从内存快照解析并完成 API key 解析（DB 密文 &gt; 环境变量 &gt;
 * 空串，ADR-0018）。 {@code apiKey} 为解析后的明文——<b>仅限进程内调用 LLM 厂商用，严禁写日志/接口回显</b>；页面回显 只用 {@code
 * apiKeySource} 与 {@code apiKeyLast4}。
 *
 * @param name provider 名（如 deepseek）
 * @param model 模型名
 * @param enabled 是否启用
 * @param isDefault 是否默认 provider（全局恰一，写路径互斥维护）
 * @param fallback fallback provider 名（可为 null）
 * @param inputPricePerMillion 输入单价（元/百万 token）
 * @param outputPricePerMillion 输出单价（元/百万 token）
 * @param baseUrl OpenAI 兼容端点（RESTART 级：消费点读启动期冻结值，页面明示重启生效）
 * @param apiKey 解析后的明文 key（DB>ENV，未配置为空串；进程内使用，永不回显）
 * @param apiKeySource key 来源（DB / ENV / NONE）
 * @param apiKeyLast4 尾 4 位（脱敏展示）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeLlmProvider(
        String name,
        String model,
        boolean enabled,
        boolean isDefault,
        String fallback,
        double inputPricePerMillion,
        double outputPricePerMillion,
        String baseUrl,
        String apiKey,
        ApiKeySource apiKeySource,
        String apiKeyLast4) {

    /** API key 解析来源（ADR-0018 双来源回落）。 */
    public enum ApiKeySource {
        /** 页面写入的 DB 密文解密所得 */
        DB,
        /** 环境变量（{@code LlmDefaults#envApiKey} 按 provider 名解析，如 DEEPSEEK_API_KEY） */
        ENV,
        /** 未配置（adapter inert） */
        NONE
    }

    /** 是否已有可用 key（DB 或 ENV）。 */
    public boolean hasApiKey() {
        return apiKeySource != ApiKeySource.NONE && apiKey != null && !apiKey.isBlank();
    }
}
