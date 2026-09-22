package com.info.platform.application.ai;

/**
 * 用户当日 LLM token 预算状态（T30 成本报表告警口径）。
 *
 * <p>与预算守卫同源（{@code LlmCostBudget#budgetWarnRatio}）：NORMAL 未达告警线 / WARNING 已越告警线 （默认 80%，余量告急）/
 * EXHAUSTED 已耗尽（后续调用将被 429 拦截，llm_call_log 落 REJECTED 行）。
 */
public enum LlmBudgetStatus {
    /** 未达告警线。 */
    NORMAL,
    /** 已越告警线（预算 × budget-warn-ratio），余量告急。 */
    WARNING,
    /** 已耗尽，调用被拦截。 */
    EXHAUSTED
}
