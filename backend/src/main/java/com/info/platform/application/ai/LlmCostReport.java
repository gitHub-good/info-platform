package com.info.platform.application.ai;

import java.util.List;

/**
 * LLM 成本报表视图（T30，GET /api/v1/llm-cost-report 响应体）。
 *
 * <p>窗口口径：{@code window} 为 today/7d/30d；{@code windowStart} 为窗口起点（ISO-8601 UTC 文本）。 汇总口径：
 *
 * <ul>
 *   <li>总调用次数 = 窗口内全部留痕行数（含缓存命中与预算拒绝——缓存命中是「省下的调用」、拒绝是「拦下的调用」，均是治理视图）。
 *   <li>成功率 = SUCCESS / (SUCCESS + FAILED)：REJECTED 未发起外呼，不入分母；缓存命中计入 SUCCESS（有响应即服务成功）。
 *   <li>缓存命中率 = cache_hit 行数 / 总行数（缓存命中即 0 成本，命中率直接映射省钱比例）。
 *   <li>成本 = 各行 cost_micros 求和（微元，整数防浮点漂移；未配单价 provider 计 0，为下界口径）。
 * </ul>
 *
 * @param providers 按 provider 维度分布（null provider 归「未发起」），成本降序
 * @param scenes 按场景键（= briefTypeKey）分布，成本降序
 * @param topUserBudgets 今日（非所选窗口）用量 Top 用户预算余量与告警状态，用量降序
 */
public record LlmCostReport(
        String window,
        String windowStart,
        long totalCalls,
        long successCalls,
        long failedCalls,
        long rejectedCalls,
        long cacheHits,
        double successRate,
        double cacheHitRate,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long costMicros,
        long dailyBudgetTokens,
        double budgetWarnRatio,
        List<ProviderCost> providers,
        List<SceneCost> scenes,
        List<UserBudget> topUserBudgets) {

    /** provider 维度成本分布。 */
    public record ProviderCost(
            String provider,
            long calls,
            long successCalls,
            long failedCalls,
            long totalTokens,
            long costMicros) {}

    /** 场景维度成本分布（scene = briefTypeKey，如 "1" 个股简报）。 */
    public record SceneCost(String scene, long calls, long totalTokens, long costMicros) {}

    /** 用户预算余量与告警状态（今日口径）。 */
    public record UserBudget(
            long userId,
            long usedTokens,
            long budgetTokens,
            long remainingTokens,
            LlmBudgetStatus status) {}
}
