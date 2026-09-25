package com.info.platform.domain.ai;

import java.util.List;

/**
 * LLM 调用请求（纯 JDK record，领域层端口入参）。
 *
 * <p>字段对齐 OpenAI 兼容 chat/completions 契约（Spike-2 §4）： {@code messages} 对话上下文； {@code model}
 * 可选模型覆盖（null/空 → 由 provider adapter 用配置的默认模型，如 {@code deepseek-flash}）； {@code temperature}/{@code
 * maxTokens} 采样参数； {@code responseFormatType} 结构化输出约束（{@code "json_object"} 启用 JSON mode，null
 * 为纯文本）； {@code briefTypeKey} 简报类型键（如 {@code "1"} 个股/{@code "5"} 行业归类），用作缓存分区键 与成本归因，T21 调用时传入；
 * {@code cacheable} 是否参与本地缓存（缺省 true；M15 管道批量场景 {@code false} 绕开——同批重试命中同一份坏输出即死循环， ADR-0046 裁决 2）。
 *
 * <p>领域层只定义调用契约——provider 选择 / fallback / 成本上限 / 缓存均为基础设施层职责（见 {@code LlmGatewayImpl}）。
 */
public record LlmRequest(
        List<ChatMessage> messages,
        String model,
        double temperature,
        int maxTokens,
        String responseFormatType,
        String briefTypeKey,
        boolean cacheable) {

    /** JSON mode 常量（对齐 OpenAI {@code response_format.type}）。 */
    public static final String JSON_OBJECT = "json_object";

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * AI 简报常用工厂：JSON mode + 默认采样（temperature 0.3）+ 参与缓存（既有简报调用语义不变）。
     *
     * <p>maxTokens 8192（2026-09-25 上调，原 Spike-2 基线 2048）：M12 详情分区分页扩容（公告 10 条/政策 30
     * 条）后简报上下文变大，输出实测顶格 2048 被截断 → JSON 半截解析失败（llm_call_log 146 行 output=2048 实证）。8192 覆盖
     * deepseek-flash/glm-4-flash 输出窗；成本侧由日预算护栏（LlmCostGuard）兜底。
     *
     * @param messages 对话上下文（system prompt 须含 "json" 字样以满足 DeepSeek JSON mode 前提）
     * @param briefTypeKey 简报类型键，缓存分区 + 成本归因
     */
    public static LlmRequest json(List<ChatMessage> messages, String briefTypeKey) {
        return new LlmRequest(messages, null, 0.3, 8192, JSON_OBJECT, briefTypeKey, true);
    }

    /**
     * 管道批量调用工厂（M15 / ADR-0046 裁决 2）：自定采样（管道 temperature 0.1，不走简报 0.3 工厂）+ {@code cacheable=false}
     * 绕开本地缓存——批量/拆批重试本质无重复语义，缓存命中坏输出即重试死循环。
     *
     * @param messages 对话上下文
     * @param briefTypeKey 场景键（如 {@code "5"} 行业归类——scene 留痕与护栏成本口径键）
     * @param temperature 采样温度（管道缺省 0.1）
     * @param maxTokens 输出上限（管道缺省 8192）
     */
    public static LlmRequest pipeline(
            List<ChatMessage> messages, String briefTypeKey, double temperature, int maxTokens) {
        return new LlmRequest(
                messages, null, temperature, maxTokens, JSON_OBJECT, briefTypeKey, false);
    }
}
