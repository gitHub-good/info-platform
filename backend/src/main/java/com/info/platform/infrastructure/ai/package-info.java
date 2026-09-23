/**
 * DDD layer 'infrastructure' / domain 'ai' · LLM 网关与 provider adapter（T19）+ 提示词模板仓储（T20）。
 *
 * <p>实现 {@code domain.ai.LlmGateway} 端口： {@link com.info.platform.infrastructure.ai.LlmGatewayImpl}
 * 编排 default/fallback 切换 + 成本上限 + 缓存； {@link
 * com.info.platform.infrastructure.ai.AbstractOpenAiCompatProvider} + {@link
 * com.info.platform.infrastructure.ai.DeepSeekAdapter}/{@link
 * com.info.platform.infrastructure.ai.GlmAdapter} 为各厂商 provider adapter（OpenAI 兼容，Spring RestClient
 * 直调）； {@link com.info.platform.infrastructure.ai.LlmDefaults} 承载 {@code llm.*} 多 provider
 * 内置缺省（ADR-0008 / ADR-0020，运行时权威在配置中心）。 T20 增 {@link
 * com.info.platform.infrastructure.ai.PromptTemplatePO} + {@link
 * com.info.platform.infrastructure.ai.PromptTemplateMapper} + {@link
 * com.info.platform.infrastructure.ai.PromptTemplateRepositoryImpl}（实现 {@code
 * PromptTemplateRepository} 加载端口，V8 播种驱动）。
 *
 * <p>对齐技术方案 §4.4、ADR-0004 / ADR-0008 / ADR-0005 / ADR-0010 与 Spike-2 调研报告。
 */
package com.info.platform.infrastructure.ai;
