package com.info.platform.domain.ai;

/**
 * 占位符描述符（领域层，T46 / ADR-0022）：模板占位符 {@code {{key}}} 的键名 + 一句话用途说明。
 *
 * <p>由各上下文装配器以 {@code providedPlaceholders()} 自述（描述符与 {@code ctx.put} 同文件同序维护）， {@code
 * PromptPlaceholderRegistry} 聚合后同时供只读接口（编辑器对照区）与保存校验 V5（unknown 基准）消费—— 单一事实源，防两份清单漂移。纯 JDK
 * record，不依赖框架。
 */
public record PlaceholderDescriptor(String key, String description) {}
