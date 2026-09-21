package com.info.platform.application.push;

import com.info.platform.domain.push.PushRecord;
import java.time.Instant;

/**
 * 推送历史视图（history 接口返回项），应用层 DTO。
 *
 * <p>从 {@link PushRecord} 投影：{@code type} 用 SSE 事件名（小写下划线，对齐 §4.1.3），便于前端按事件类型渲染； {@code status}
 * 为推送状态码（0 待推 / 1 已推 / 2 失败）。
 */
public record NotificationView(
        Long id,
        String type,
        Long subjectId,
        String refId,
        String content,
        int status,
        Instant pushedAt,
        Instant createdAt) {

    public static NotificationView from(PushRecord record) {
        return new NotificationView(
                record.getId(),
                record.getPushType().eventName(),
                record.getSubjectId().orElse(null),
                record.getRefId().orElse(null),
                record.getContent(),
                record.getStatus().code(),
                record.getPushedAt().orElse(null),
                record.getCreatedAt().orElse(null));
    }
}
