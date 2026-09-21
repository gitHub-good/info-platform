package com.info.platform.domain.ai;

/**
 * LLM 成本预算端口（依赖倒置：领域层定义、基础设施层 {@code LlmCostGuard} 实现）。
 *
 * <p>领域层纯净接口，不依赖框架类型。供应用层 {@code AIBriefService} 在 POST 受理时同步预检单用户日 token 预算 （fail-fast 返回 429，技术方案
 * §4.1.4），使应用层不直接依赖基础设施实现（守护分层：避 application↔infrastructure 循环依赖）。
 *
 * <p>实际计费入账（{@code recordUsage}）与预算计数（Caffeine）由基础设施层 {@code LlmGatewayImpl} 在 chat 时调用 {@code
 * LlmCostGuard} 完成（本端口只暴露应用层所需的预检动作）。
 */
public interface LlmCostBudget {

    /**
     * 检查当日预算：已用 ≥ 预算抛 {@link com.info.platform.domain.common.BusinessException}（{@link
     * com.info.platform.domain.common.ErrorCode#AI_QUOTA_EXHAUSTED}，429 语义）。
     *
     * @param userId 用户 ID（≤0 跳过，如系统定时任务）
     */
    void checkBudget(long userId);
}
