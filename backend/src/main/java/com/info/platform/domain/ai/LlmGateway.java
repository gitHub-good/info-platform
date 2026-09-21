package com.info.platform.domain.ai;

/**
 * LLM 网关端口（依赖倒置：领域层定义、基础设施层 {@code LlmGatewayImpl} 实现）。
 *
 * <p>端口只定义 {@link #chat} 契约——provider 选择 / fallback 切换 / 成本上限 / 缓存均为基础设施层编排职责， 不出现在领域层接口中（对齐
 * ADR-0004 / ADR-0008 + 技术方案 §4.4）。
 *
 * <p>调用方（T21 AIBriefService，应用层）从 {@code prompt_template} 加载 + 填充聚合上下文后调用， 不关心厂商与重试。
 *
 * <p>异常约定：
 *
 * <ul>
 *   <li>成本上限触发（单用户日 token 预算耗尽）→ 抛 {@code BusinessException(AI_QUOTA_EXHAUSTED)}（429 语义，见 {@code
 *       ErrorCode}）。
 *   <li>所有 provider 均失败（超时/限频/错误且 fallback 链耗尽）→ 抛 {@link LlmException}。
 * </ul>
 */
public interface LlmGateway {

    /**
     * 调用 LLM 生成内容。
     *
     * @param request 对话请求（messages + 采样参数 + 简报类型键）
     * @return 响应（content + usage + provider + model）
     * @throws com.info.platform.domain.common.BusinessException 成本上限触发
     * @throws LlmException 所有 provider 失败
     */
    LlmResponse chat(LlmRequest request);
}
