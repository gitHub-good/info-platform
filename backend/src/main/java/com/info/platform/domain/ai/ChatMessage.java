package com.info.platform.domain.ai;

/**
 * LLM 对话消息（OpenAI 兼容 {@code messages[]} 单元，纯 JDK 值对象）。
 *
 * <p>{@code role} 取 {@code system}/{@code user}/{@code assistant}；{@code content} 为原文。 由应用层（T21
 * AIBriefService）从 {@code prompt_template} 加载并填充聚合上下文后组装传入 gateway，gateway 不关心业务语义。
 */
public record ChatMessage(String role, String content) {}
