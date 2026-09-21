/**
 * DDD layer 'application' / domain 'ai' · 提示词模板服务（T20）。
 *
 * <p>{@link com.info.platform.application.ai.PromptTemplateService} 加载启用模板（按 {@code briefType} +
 * {@code status=1} 最新版本）+ 渲染占位符 {@code {{key}}} 为 {@code [system, user]} {@link
 * com.info.platform.domain.ai.ChatMessage} 列表，供 T21 {@code AIBriefService} 以 {@code
 * LlmRequest.json(messages, briefTypeKey)} 传入 {@link com.info.platform.domain.ai.LlmGateway}。
 * 对齐技术方案 §4.3 流程 2 + §4.4 + Spike-2 §7。
 *
 * <p>Scaffold per tech design sec 2 (Container) & sec 4.4 (DDD layering, ADR-0007). Cross-domain
 * collaboration goes via domain events; no cross-domain inner-class import.
 */
package com.info.platform.application.ai;
