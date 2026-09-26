package com.info.platform.application.recommendation;

/**
 * 采纳统计视图（M16 T134，方案 §4.7 {@code GET /api/v1/recommendations/stats}，adopt-v1 无表派生——沿 M15 护栏先例）：
 * 三面同源（推荐中心/验收/北极星），按日可查（缺省当日 Asia/Shanghai）。
 *
 * @param date 统计日（yyyy-MM-dd，Asia/Shanghai 日界）
 * @param pushDelivered 曝光①推送送达：push_record(type=10, status=SUCCESS, pushed_at ∈ 当日)；SILENT 未送达不计
 * @param viewExposed 曝光②视口曝光：reading_event(RECOMMENDATION_VIEW, contentRef=cardId, 当日 distinct
 *     卡)——单列展示
 * @param adopted 采纳：点击（read 展开即采纳）/ USEFUL / 加自选去重计 1（ACT 埋点 distinct 卡 = card.adopted 当日首置， §4.7
 *     同点写入恒等）
 * @param adoptRate 采纳率 = 采纳 ÷ 曝光（①+②）；无曝光样本记 null（P50/P90 先例）。北极星 ≥30% 为 M18 全量口径， 本期验收 = 口径上线 +
 *     对账一致
 * @param basis 口径版本串（adopt-v1；口径调整升版，不静默）
 */
public record RecommendationStatsView(
        String date,
        long pushDelivered,
        long viewExposed,
        long adopted,
        Double adoptRate,
        String basis) {

    /** 口径版本串（adopt-v1）。 */
    public static final String BASIS = "adopt-v1";
}
