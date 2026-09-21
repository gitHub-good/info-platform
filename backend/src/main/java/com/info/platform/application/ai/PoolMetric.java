package com.info.platform.application.ai;

/**
 * 自选池单标的指标快照（值对象，T23 每日推荐上下文装配用）。
 *
 * <p>对齐 PRD 故事 2 场景 2「信息面活跃度 + 事件重要性」： {@code changePct} 绝对值表征行情活跃度， {@code announceCount}/{@code
 * newsCount} 表征事件重要性（近 24h 公告 / 近 7 天新闻条数）。由 {@link DailyRecommendationContextBuilder} 经行情/公告/新闻
 * SourceAdapter 取数（复用 adapter 享缓存 + 弹性降级）后组装。
 *
 * <p>{@code changePct} 为原始涨跌幅（非绝对值），符号保留供 LLM 判断方向；规则兜底排序时取绝对值计入活跃度分（见 {@link
 * DailyRecommendationService}）。源降级（MISSING/FAILED）时对应指标归 0——不阻断整池装配（对齐 §5 降级预案）。
 *
 * @param subjectCode 标的内部统一代码（如 SH600519）
 * @param subjectName 标的名称
 * @param changePct 涨跌幅 %（源降级时 0）
 * @param announceCount 近 24h 公告条数（源降级时 0）
 * @param newsCount 近 7 天相关新闻条数（源降级时 0）
 */
public record PoolMetric(
        String subjectCode,
        String subjectName,
        double changePct,
        int announceCount,
        int newsCount) {

    /** 活跃度综合分（规则兜底排序用）：|涨跌幅| + 公告数×2 + 新闻数×1。 */
    public double activityScore() {
        return Math.abs(changePct) + announceCount * 2.0 + newsCount;
    }
}
