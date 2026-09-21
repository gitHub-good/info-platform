/**
 * DDD layer 'infrastructure' / domain 'ai' · LLM 网关与 provider adapter（T19）。
 *
 * <p>实现 {@code domain.ai.LlmGateway} 端口： {@link com.info.platform.infrastructure.ai.LlmGatewayImpl}
 * 编排 default/fallback 切换 + 成本上限 + 缓存； {@link
 * com.info.platform.infrastructure.ai.AbstractOpenAiCompatProvider} + {@link
 * com.info.platform.infrastructure.ai.DeepSeekAdapter}/{@link
 * com.info.platform.infrastructure.ai.GlmAdapter} 为各厂商 provider adapter（OpenAI 兼容，Spring RestClient
 * 直调）； {@link com.info.platform.infrastructure.ai.LlmConfig} 绑定 {@code llm.*} 多 provider
 * 配置（ADR-0008）。
 *
 * <p>对齐技术方案 §4.4、ADR-0004 / ADR-0008 / ADR-0005 / ADR-0010 与 Spike-2 调研报告。
 */
package com.info.platform.infrastructure.ai;
