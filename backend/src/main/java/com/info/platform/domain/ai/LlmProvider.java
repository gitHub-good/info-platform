package com.info.platform.domain.ai;

/**
 * LLM 厂商枚举（纯 JDK，领域层）。
 *
 * <p>与 {@code application.yml} 的 {@code llm.providers[].name} 对应：枚举名表达厂商， {@link #configName}
 * 为配置键字符串。T19 落地 DeepSeek（默认）+ GLM（fallback）；QIANWEN/WENXIN/KIMI 为预留（ADR-0008 enabled=false，按需启用时各加
 * provider adapter）。
 */
public enum LlmProvider {
    DEEPSEEK("deepseek"),
    GLM("glm"),
    /** 通义千问（预留） */
    QIANWEN("qwen"),
    /** 文心一言（预留） */
    WENXIN("wenxin"),
    /** Kimi / Moonshot（预留） */
    KIMI("kimi");

    private final String configName;

    LlmProvider(String configName) {
        this.configName = configName;
    }

    /** 配置键（对齐 yaml {@code llm.providers[].name}），如 {@code "deepseek"}。 */
    public String configName() {
        return configName;
    }
}
