package com.info.platform.application.ai;

import java.util.List;
import java.util.Map;

/**
 * LLM 配置全量视图（T35，接口层回显 DTO，对齐方案 §4.4.1 契约）。
 *
 * <p>安全红线（ADR-0018）：API key 永不回明文，仅 {@link ApiKeyView 脱敏态}（status/source/last4）； {@code
 * effectiveModes} 为逐字段生效级别（{@code "LIVE"} 保存即生效 / {@code "RESTART"} 重启后生效， 方案 §4.2
 * 分级），前端徽章与保存反馈按此渲染、缺失一律按 RESTART 显示（UI 方案 §4.2）。 时间字段一律 ISO-8601 字符串（UTC），对齐契约样例与既有 JobLog 视图惯例。
 *
 * @param global 全局参数（含只读展示项 cacheMaximumSize，RESTART 级走 yml）
 * @param providers provider 配置列表（既有 4 条，本期不增删）
 * @param apiKeyWriteEnabled CONFIG_SECRET 是否已配置（false = key 写入降级 30064，页面只读态）
 */
public record LlmConfigView(
        GlobalConfigView global, List<ProviderConfigView> providers, boolean apiKeyWriteEnabled) {

    /** 生效级别值：保存即生效（下一次调用/新缓存条目）。 */
    public static final String EFFECTIVE_LIVE = "LIVE";

    /** 生效级别值：重启后生效（保存持久化，消费点读启动期冻结快照）。 */
    public static final String EFFECTIVE_RESTART = "RESTART";

    /**
     * {@code llm.global} 回显。
     *
     * @param todayUsedTokens 当前用户今日已用 token（预算二次确认对话框比对口径）
     */
    public record GlobalConfigView(
            long timeoutSeconds,
            int retry,
            long dailyTokenBudgetPerUser,
            double budgetWarnRatio,
            long cacheDefaultTtlSeconds,
            Map<String, Long> cacheTtlSeconds,
            long cacheMaximumSize,
            long todayUsedTokens,
            String updatedAt,
            Map<String, String> effectiveModes) {}

    /** {@code llm.provider.{name}} 回显（key 脱敏；baseUrl 契约字段 baseUrlEffective 兼容保留）。 */
    public record ProviderConfigView(
            String name,
            String model,
            boolean enabled,
            boolean isDefault,
            String fallback,
            String baseUrl,
            String baseUrlEffective,
            double inputPricePerMillion,
            double outputPricePerMillion,
            ApiKeyView apiKey,
            String updatedAt,
            Map<String, String> effectiveModes) {}

    /** API key 脱敏态（明文永不出现）。 */
    public record ApiKeyView(String status, String source, String last4) {

        /** 已配置（DB 密文或环境变量任一来源）。 */
        public static final String STATUS_CONFIGURED = "CONFIGURED";

        /** 未配置（adapter inert）。 */
        public static final String STATUS_NOT_SET = "NOT_SET";
    }
}
