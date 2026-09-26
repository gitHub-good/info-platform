package com.info.platform.domain.recommendation;

/** 反馈动作（{@code recommendation_feedback.action}，M16 方案 §4.7 / REQ 拍板六）：四动作闭环。 */
public enum FeedbackAction {
    /** 有用（正反馈：adopted 置 1 + ACT 埋点 + 画像回流）。 */
    USEFUL,
    /** 不感兴趣（负反馈：mute UPSERT 降频 7 天，滚动 30 天 ≥3 次升级 30 天静默；不计负画像）。 */
    DISLIKE,
    /** 加自选（复用 WatchlistService；adopted 置 1 + ACT 埋点）。 */
    ADD_WATCHLIST,
    /** 撤销降频（mute → LIFTED；幂等 no-op）。 */
    UNDO_MUTE;

    /** 从持久化/入参文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static FeedbackAction fromName(String name) {
        if (name != null) {
            for (FeedbackAction action : values()) {
                if (action.name().equals(name)) {
                    return action;
                }
            }
        }
        throw new IllegalArgumentException("未知 feedback action: " + name);
    }
}
