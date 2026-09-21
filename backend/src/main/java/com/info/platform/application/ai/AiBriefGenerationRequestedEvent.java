package com.info.platform.application.ai;

/**
 * AI 简报生成请求事件（应用层内部异步触发 POJO，T21）。
 *
 * <p>{@link AIBriefService#createBrief} 同步受理后发布本对象，{@link AIBriefService#onGenerationRequested} 以
 * {@code @Async @EventListener} 消费——经 Spring 事件多播器调用代理（非自调用），使 {@code @Async} 生效（避 自调用绕过代理的坑，对齐
 * {@code PushService.onAnomalyDetected} 模式）。承载 {@code userId} 是因 ThreadLocal 的 {@code UserContext}
 * 不跨异步线程传递，Worker 须显式 {@code UserContext.set} 才能计入成本配额（LlmCostGuard 据此计费）。
 *
 * <p>非领域事件（不发跨域、无推送语义），仅作进程内异步编排触发器，故置于应用层而非 domain/ai。
 *
 * @param taskId 已受理任务 id
 * @param userId 受理时的认证用户 id（异步 Worker 据此设置 UserContext）
 */
public record AiBriefGenerationRequestedEvent(Long taskId, long userId) {}
