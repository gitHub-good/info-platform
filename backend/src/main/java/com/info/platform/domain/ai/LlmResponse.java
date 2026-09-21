package com.info.platform.domain.ai;

/**
 * LLM 调用响应（纯 JDK record，领域层端口出参）。
 *
 * <p>{@code content} 为模型返回的原文（JSON mode 下为 JSON 字符串，可能空/非法，解析责任在应用层 T21）； {@code usage} token
 * 用量（成本治理）； {@code provider}/{@code model} 供回溯（T21 写 {@code ai_brief.model}）。
 *
 * @param content 模型返回内容（可能为空串或非法 JSON，由调用方解析兜底）
 * @param usage token 用量；缺失时为 {@code new LlmUsage(0,0)}
 * @param provider 实际服务的厂商
 * @param model 实际使用的模型名（含 request 覆盖）
 */
public record LlmResponse(String content, LlmUsage usage, LlmProvider provider, String model) {}
