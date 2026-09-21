package com.info.platform.domain.ai;

import java.util.Objects;

/**
 * AI 简报生成完成领域事件（AIBriefService 异步生成终态后发布）。
 *
 * <p><b>纯 POJO，不继承 Spring {@code ApplicationEvent}</b>——领域层保持纯净（ADR-0007，禁 import 框架类型，由 {@code
 * LayeredArchitectureTest} 守护）。应用层 {@code AIBriefService} 经 Spring {@code
 * ApplicationEventPublisher.publishEvent(Object)} 发布本对象（自 Spring 4.2 起接受任意 POJO），下游（T14
 * PushService）用 {@code @EventListener} 监听本类型消费——经 SSE 推给订阅用户（{@code ai_brief} 事件类型，§4.1.3）。这样既走
 * Spring 事件驱动机制（ADR-0006），又不污染领域层。
 *
 * <p>跨域协作：本事件在 ai 域发布、push 域消费（跨域），不跨域 import 内部类——发布方只传本 POJO，消费方据类型匹配。参考 {@code
 * AnomalyDetectedEvent}（T13 异动事件）模式。
 *
 * <p>不可变。承载推送所需的最小信息：{@code taskId}（前端轮询/跳转）/ {@code userId}（推送目标）/ {@code subjectId}（标的维度，
 * 每日推荐型可空）。{@code status} 让推送侧区分「完成可展示」与「待核实/失败」。
 */
public final class AiBriefDoneEvent {

    private final Long taskId;
    private final long userId;
    private final Long subjectId;
    private final BriefStatus status;

    public AiBriefDoneEvent(Long taskId, long userId, Long subjectId, BriefStatus status) {
        this.taskId = Objects.requireNonNull(taskId, "taskId 必填");
        this.userId = userId;
        this.subjectId = subjectId;
        this.status = Objects.requireNonNull(status, "status 必填");
    }

    public Long getTaskId() {
        return taskId;
    }

    public long getUserId() {
        return userId;
    }

    /** 标的维度（每日推荐型无单一标的，为 null）。 */
    public Long getSubjectId() {
        return subjectId;
    }

    public BriefStatus getStatus() {
        return status;
    }
}
