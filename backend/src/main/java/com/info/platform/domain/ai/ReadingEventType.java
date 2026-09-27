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
 *   <li>{@link #FEED}：信息流「点原文」阅读（contentRef=FeedItem 稳定 contentId，REQ-20260925-08 / ADR-0019
 *       解除延后；带标的关联的条目经 subjectCode 解析计入画像，同权口径）。
 *   <li>{@link #RECOMMENDATION_VIEW}：推荐卡片视口曝光（M16 T134，contentRef=recommendation_card.id）—— 采纳统计
 *       adopt-v1 曝光②口径（§4.7），前端推荐中心页埋点。
 *   <li>{@link #RECOMMENDATION_ACT}：推荐采纳动作（M16 T134，contentRef=recommendation_card.id）—— 与
 *       card.adopted 条件置位同点写入（读/有用/加自选先置位成功再落 ACT，§4.7 对账恒等断言）； subjectId 经标的区首标的解析（USEFUL 画像回流）。
 *   <li>{@link #MARKET_TOP_VIEW}：全市场榜单卡曝光（M21 T184，contentRef=榜单版本内定位
 *       {@code rankDate:v{version}:r{rankNo}}——读取契约不冗余行 id，组合键稳定）——北极星采纳口径（M22 T194 首测）。
 *   <li>{@link #MARKET_TOP_ACT}：全市场榜单采纳动作（M21 T184，contentRef 同上）——与「加自选」成功同点写入
 *       （沿 M16「ACT 与 adopted 同点」先例）；subjectCode 带标的画像回流。
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
    FEED,
    /** 推荐卡片视口曝光（contentRef=recommendation_card.id，M16 采纳统计曝光②）。 */
    RECOMMENDATION_VIEW,
    /** 推荐采纳动作（contentRef=recommendation_card.id，与 adopted 置位同点写入，M16）。 */
    RECOMMENDATION_ACT,
    /** 全市场榜单卡曝光（contentRef=rankDate:v{version}:r{rankNo}，M21 T184 北极星曝光口径）。 */
    MARKET_TOP_VIEW,
    /** 全市场榜单采纳动作（contentRef 同 VIEW，与加自选成功同点写入，M21 T184）。 */
    MARKET_TOP_ACT;

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
