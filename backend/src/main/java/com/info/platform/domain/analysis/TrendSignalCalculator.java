package com.info.platform.domain.analysis;

/**
 * 走向判断规则信号层（领域纯函数，M17 T146，REQ 拍板六 / 任务 T146）：输入 = 周内热度首末对比（本周 vs 上周等长窗现算）+ 事件密度 +
 * 政策计数 → 每行业信号（升温/降温/平稳）+ 置信度（高/中/低）。<b>纯计算零 AI 成本；置信度由本层锁定（trend-v1），AI 仅语言组织不可抬高或
 * 降低</b>（输出校验在周报服务——置信度与规则层不一致即模板兜底）。
 *
 * <p>trend-v1 映射口径（版本化留档，沿 heat-v1 / recscore-v1 / adopt-v1 先例）：
 *
 * <ul>
 *   <li>信号：deltaPct ≥ +20% → 升温；≤ −20% → 降温；区间内（含双 0）→ 平稳
 *   <li>置信度高：|deltaPct| ≥ 50%（强度）且 本周事件数 ≥3（密度阈值）且 本周事件数 &gt; 上周（密度环比上升）
 *   <li>置信度中：信号达成（|deltaPct| ≥ 20%）但未满足高档三条件
 *   <li>置信度低：平稳/弱信号
 * </ul>
 */
public final class TrendSignalCalculator {

    /** 口径版本串（周报 trendJudgement.basis 与报告 basis 脚注直读）。 */
    public static final String BASIS = "trend-v1";

    /** 信号阈值：|deltaPct| 达 20% 即升温/降温（缺省拍板六-2「最显著行业」的量化锚）。 */
    static final double SIGNAL_DELTA_THRESHOLD = 20.0;

    /** 高置信强度阈值：|deltaPct| ≥ 50%。 */
    static final double STRONG_DELTA_THRESHOLD = 50.0;

    /** 高置信事件密度阈值：本周事件数 ≥3。 */
    static final long DENSITY_EVENT_THRESHOLD = 3;

    /** 趋势信号（三值，中文展示名渲染面直读）。 */
    public enum TrendSignal {
        HEATING("升温"),
        COOLING("降温"),
        STABLE("平稳");

        private final String displayName;

        TrendSignal(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 置信度三档（规则层锁定，AI 不可改）。 */
    public enum TrendConfidence {
        HIGH("高"),
        MEDIUM("中"),
        LOW("低");

        private final String displayName;

        TrendConfidence(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** 线格式反查（AI 输出篡改校验与解析容错用；未知返回 null）。 */
        public static TrendConfidence fromName(String name) {
            if (name == null) {
                return null;
            }
            for (TrendConfidence confidence : values()) {
                if (confidence.name().equalsIgnoreCase(name.trim())) {
                    return confidence;
                }
            }
            return null;
        }
    }

    /** 单行业信号输入（周/上周双窗现算值 + 事件与政策密度）。 */
    public record TrendInput(
            String industry,
            double weekScore,
            double prevWeekScore,
            long weekEvents,
            long prevWeekEvents,
            long weekPolicyEvents) {}

    /** 信号输出（signal + confidence + deltaPct，零 AI 参与）。 */
    public record TrendResult(TrendSignal signal, TrendConfidence confidence, double deltaPct) {}

    private TrendSignalCalculator() {}

    /** 单行业信号计算（trend-v1 锁定映射）。 */
    public static TrendResult compute(TrendInput input) {
        double deltaPct = deltaPctOf(input.weekScore(), input.prevWeekScore());
        TrendSignal signal =
                deltaPct >= SIGNAL_DELTA_THRESHOLD
                        ? TrendSignal.HEATING
                        : deltaPct <= -SIGNAL_DELTA_THRESHOLD ? TrendSignal.COOLING : TrendSignal.STABLE;
        return new TrendResult(signal, confidenceOf(deltaPct, input), deltaPct);
    }

    private static TrendConfidence confidenceOf(double deltaPct, TrendInput input) {
        boolean strong = Math.abs(deltaPct) >= STRONG_DELTA_THRESHOLD;
        boolean dense = input.weekEvents() >= DENSITY_EVENT_THRESHOLD
                && input.weekEvents() > input.prevWeekEvents();
        if (strong && dense) {
            return TrendConfidence.HIGH;
        }
        return Math.abs(deltaPct) >= SIGNAL_DELTA_THRESHOLD ? TrendConfidence.MEDIUM : TrendConfidence.LOW;
    }

    /** 环比：prev=0 且 score&gt;0 记 100.0，双 0 记 0（与 heat-v1 deltaPctOf 同口径）。 */
    static double deltaPctOf(double score, double prev) {
        return IndustryHeatSnapshot.deltaPctOf(score, prev);
    }
}
