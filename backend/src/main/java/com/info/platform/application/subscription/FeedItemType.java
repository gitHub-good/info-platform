package com.info.platform.application.subscription;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 个人信息流条目类型（对齐技术方案 §4.1.6 {@code GET /api/v1/feed/personal} 的 FeedItem.type）。
 *
 * <p>应用层枚举，承载三类命中内容 + 每日推荐。JSON 序列化为小写串（{@link #JsonValue}），与接口契约 {@code type:
 * announce/news/policy/recommendation} 一致。
 *
 * <ul>
 *   <li>{@link #ANNOUNCE} 公告（T05 AnnounceSourceAdapter 按标的取数后命中）
 *   <li>{@link #NEWS} 新闻（T06 NewsSourceAdapter 按标的取数后命中）
 *   <li>{@link #POLICY} 政策（T24 PolicyRepository 近期政策命中）
 *   <li>{@link #RECOMMENDATION} 每日推荐（T23 DailyRecommendationService Top5，非订阅命中）
 * </ul>
 */
public enum FeedItemType {
    ANNOUNCE("announce"),
    NEWS("news"),
    POLICY("policy"),
    RECOMMENDATION("recommendation");

    private final String jsonValue;

    FeedItemType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** JSON 序列化值（小写串）。 */
    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }
}
