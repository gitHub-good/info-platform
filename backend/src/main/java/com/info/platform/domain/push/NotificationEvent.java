package com.info.platform.domain.push;

/**
 * 推送载荷（SSE data 序列化源），领域层纯净不可变 record。
 *
 * <p>由应用层 {@code PushService} 据领域事件构造，基础设施层 {@code SseConnectionRegistry} 经 Jackson 序列化为 SSE {@code
 * data:} 字段。字段即客户端所需最小信息：
 *
 * <ul>
 *   <li>{@code type} —— SSE {@code event:} 字段名（如 {@code anomaly}），对齐 §4.1.3
 *   <li>{@code subjectId} —— 关联标的（可空，异动/事件推送必填）
 *   <li>{@code refId} —— 关联事件/简报 id（异动=anomaly_event.id，供客户端回查）
 *   <li>{@code content} —— 人读摘要（异动推送=anomaly_event.detail）
 * </ul>
 *
 * <p>注意：不提供 {@code getXxx()} 形式的 bean getter——返回 {@code Optional} 会令 Jackson 默认配置序列化失败（Optional 需
 * jdk8 模块）； record 规范访问器 {@code subjectId()} / {@code refId()} 返回原始可空类型，Jackson 经 record 组件直接序列化为
 * {@code Long/String}。
 *
 * <p>跨域协作：本类型在 push 域内闭环（构造、消费均在 push 应用层/基础设施层），不跨域 import。
 */
public record NotificationEvent(String type, Long subjectId, String refId, String content) {

    public NotificationEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type 必填");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 必填");
        }
    }

    /** 便捷构造：据 {@link PushType} 取事件名。 */
    public static NotificationEvent of(
            PushType pushType, Long subjectId, String refId, String content) {
        return new NotificationEvent(pushType.eventName(), subjectId, refId, content);
    }
}
