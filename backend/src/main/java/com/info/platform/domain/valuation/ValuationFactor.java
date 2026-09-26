package com.info.platform.domain.valuation;

import java.util.List;

/**
 * F5 估值水平（领域纯函数，条件因子默认权重 0，M20 方案 §4.2 + ADR-0058 裁决 5）：横截面口径（当日全市场）， 低估值得高分；PE→PB 回退链，缺数中性
 * 50。时序分位（历史 PE/PB 分位）待 market_daily_snapshot 序列 ≥250 交易日后 v1.x 升级（变更控制）。
 *
 * <pre>{@code
 * pePct = pe_ttm>0 标的中升序百分位（rank = 严格小于本值的行数 + 1；低 PE → 低 pct）
 * basisPct = pePct（有 PE）→ 否则 pbPct（有 PB）→ 否则缺数（F5=50 + NO_VALUATION_DATA）
 * F5 = 100 × (1 − basisPct/100)
 * }</pre>
 */
public final class ValuationFactor {

    /** 缺数中性分（§4.1 列缺省同值）。 */
    public static final double NEUTRAL_SCORE = 50.0;

    private ValuationFactor() {}

    /** F5 结果（basis PE/PB/null、pct [0,100]、neutral = 双缺）。 */
    public record Result(
            double score, String basis, Double pe, Double pb, Double pct, boolean neutral) {}

    /**
     * 计算 F5（纯函数）。
     *
     * @param peTtm 本标的 PE-TTM（null 或 ≤0 = 缺）
     * @param pb 本标的 PB（null 或 ≤0 = 缺）
     * @param positivePeCrossSection 当日全市场 pe&gt;0 值集（升序百分位分母）
     * @param positivePbCrossSection 当日全市场 pb&gt;0 值集
     */
    public static Result compute(
            Double peTtm,
            Double pb,
            List<Double> positivePeCrossSection,
            List<Double> positivePbCrossSection) {
        if (valid(peTtm)) {
            double pct = percentileOf(peTtm, positivePeCrossSection);
            return new Result(100.0 - pct, "PE", peTtm, pb, pct, false);
        }
        if (valid(pb)) {
            double pct = percentileOf(pb, positivePbCrossSection);
            return new Result(100.0 - pct, "PB", peTtm, pb, pct, false);
        }
        return new Result(NEUTRAL_SCORE, null, peTtm, pb, null, true);
    }

    /** 升序百分位 [0,100]：rank = 严格小于本值的行数 + 1（并列同名次），pct = 100 × (rank−1) / max(1, N−1)。 */
    static double percentileOf(double value, List<Double> crossSection) {
        List<Double> values =
                crossSection == null ? List.<Double>of() : crossSection.stream().sorted().toList();
        long strictlyLess = values.stream().filter(v -> v < value).count();
        long n = values.size();
        double rank = strictlyLess + 1;
        return 100.0 * (rank - 1) / Math.max(1, n - 1);
    }

    private static boolean valid(Double value) {
        return value != null && value > 0;
    }
}
