package com.info.platform.domain.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 总分合成器（领域纯函数，M20 方案 §4.2「总分与标签」+ ADR-0058 裁决 4）：
 *
 * <pre>{@code
 * total        = Σ w_i × F_i / Σ w_i    （配置未归一自动归一；结果 round1）
 * breakthrough = (F1 ≥ btCatalystMin) ∧ (F2 ≥ btConductionMin) ∧ (F4 ≥ btRiskMin)
 * }</pre>
 *
 * <p>缺省权重 (0.40, 0.20, 0.20, 0.20, 0.00)——F5 条件因子默认不进基线（裁决 5）。同输入同权重同分（幂等红线）； weight_basis 指纹串由
 * {@link ValuationParams#basis()} 派生入快照行（按当时权重复现的审计锚）。
 */
public final class ScoreComposer {

    private ScoreComposer() {}

    /** 合成结果（totalScore [0,100] round1 + 「有突破」三阈值标签）。 */
    public record Composed(double totalScore, boolean breakthrough) {}

    /** 合成总分与标签（纯函数；输入建议用落库粒度因子分——展示总分 = 展示分维的加权均值，审计自洽）。 */
    public static Composed compose(
            double fCatalyst,
            double fConduction,
            double fFundamental,
            double fRisk,
            double fValuation,
            ValuationParams params) {
        double weightSum = params.weightSum();
        double total =
                weightSum <= 0.0
                        ? 0.0
                        : (params.wCatalyst() * fCatalyst
                                        + params.wConduction() * fConduction
                                        + params.wFundamental() * fFundamental
                                        + params.wRisk() * fRisk
                                        + params.wValuation() * fValuation)
                                / weightSum;
        boolean breakthrough =
                fCatalyst >= params.btCatalystMin()
                        && fConduction >= params.btConductionMin()
                        && fRisk >= params.btRiskMin();
        return new Composed(round1(total), breakthrough);
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
