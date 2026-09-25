package com.info.platform.domain.ai;

/**
 * 阅读事件内容类型（reading_event.content_type 持久化为枚举名文本，T29）。
 *
 * <p>领域层纯净枚举（仅 JDK），对齐 {@code LlmCallStatus} 模式。枚举值对应前端埋点页面：
 *
 * <ul>
 *   <li>{@link #SUBJECT_DETAIL}：标的详情聚合页（contentRef=标的代码如 SH600519）。
 *   <li>{@link #POLICY}：政策时事详情（contentRef=政策 id）。
 *   <li>{@link #AI_BRIEF}：AI 简报阅读（contentRef=简报 taskId）。
 *   <li>{@link #FEED}：个人信息流「点原文」阅读（contentRef=FeedItem 稳定 contentId，REQ-20260925-08 / ADR-0019
 *       解除延后；带标的关联的条目经 subjectCode 解析计入画像，同权口径）。
 * </ul>
 */
public enum ReadingEventType {
    /** 标的详情页阅读。 */
    SUBJECT_DETAIL,
    /** 政策详情阅读。 */
    POLICY,
    /** AI 简报阅读。 */
    AI_BRIEF,
    /** 信息流「点原文」阅读（contentRef=条目稳定 contentId，非合成游标 id）。 */
    FEED;

    /** 持久化用枚举名（与 DDL content_type TEXT 一致）。 */
    public String persistentName() {
        return name();
    }

    /** 从持久化/请求文本反查枚举；未知值抛非法参数（由调用方转业务异常）。 */
    public static ReadingEventType fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("contentType 为空");
        }
        for (ReadingEventType t : values()) {
            if (t.name().equalsIgnoreCase(name.trim())) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 contentType: " + name);
    }
}
