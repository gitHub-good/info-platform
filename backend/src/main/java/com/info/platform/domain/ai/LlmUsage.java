package com.info.platform.domain.ai;

/**
 * LLM 调用 token 用量（OpenAI 兼容 {@code usage} 子集，纯 JDK 值对象）。
 *
 * <p>仅保留成本治理所需两个量：{@code promptTokens}（输入）+ {@code completionTokens}（输出）。 {@link #totalTokens} 供
 * {@code LlmCostGuard} 计入单用户日预算；T30 成本报表可按 {@code provider}/{@code model} 维度累计。
 */
public record LlmUsage(int promptTokens, int completionTokens) {

    /** 输入+输出合计，用于预算计数。 */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
