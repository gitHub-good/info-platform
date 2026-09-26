package com.info.platform.domain.valuation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F2 行业热度传导（领域纯函数，M20 方案 §4.2 + ADR-0058 裁决 2/3）：
 *
 * <pre>{@code
 * heatNorm(i) = (31 − rank_H24(i)) / 30      31 行申万 H24 heat 降序名次，并列按行业名升序（确定性破并列）
 * raw2 = max_{i∈assoc(s)} weight(i) × heatNorm(i) × 0.5^(lastSeenAge / W2)
 * F2   = 100 × raw2                          （raw2 已落 [0,1]）
 * }</pre>
 *
 * <p>关联集由 {@link IndustryAssociator} 双路派生（事件回联 + 资讯归类回联）；关联空 → F2=0（NO_ASSOC_INDUSTRY 由编排层标注）；
 * 热度快照缺行行业 heatNorm 记 0（降级不放大）。31 行内小范围名次归一是固定枚举集归一，不构成全市场耦合（裁决 3 例外）。
 */
public final class ConductionFactor {

    /** 申万一级行业标准行数（heatNorm 分母 = 行数 − 1 = 30，方案冻结）。 */
    static final int SW_INDUSTRY_ROWS = 31;

    private ConductionFactor() {}

    /** 关联明细条目（§4.5 契约 conduction.assoc 元素）。 */
    public record AssocDetail(
            String industry, double heatH24, double heatNorm, long lastSeenAge, String source) {}

    /** F2 结果（score [0,100] + 关联明细按贡献降序 cap 10）。 */
    public record Result(double score, List<AssocDetail> assoc) {}

    /**
     * 计算 F2（纯函数）。
     *
     * @param associations 标的关联集（可为空）
     * @param h24Heat H24 热度快照行（通常 31 行常驻；缺行行业记 0）
     * @param params 参数（取 assocWindowDays 为关联半衰期窗）
     */
    public static Result compute(
            List<IndustryAssociator.Association> associations,
            List<HeatRow> h24Heat,
            ValuationParams params) {
        Map<String, Double> heatNorm = heatNormByIndustry(h24Heat);
        List<AssocDetail> details = new ArrayList<>();
        double raw2 = 0.0;
        for (IndustryAssociator.Association association :
                associations == null ? List.<IndustryAssociator.Association>of() : associations) {
            double norm = heatNorm.getOrDefault(association.industry(), 0.0);
            double decay =
                    FactorMath.decay(association.lastSeenAgeDays(), params.assocWindowDays());
            double contribution = association.weight() * norm * decay;
            raw2 = Math.max(raw2, contribution);
            details.add(
                    new AssocDetail(
                            association.industry(),
                            heatScoreOf(h24Heat, association.industry()),
                            norm,
                            association.lastSeenAgeDays(),
                            association.source().name()));
        }
        details.sort(
                Comparator.comparingDouble(
                                (AssocDetail detail) ->
                                        detail.heatNorm()
                                                * FactorMath.decay(
                                                        detail.lastSeenAge(),
                                                        params.assocWindowDays()))
                        .reversed()
                        .thenComparing(AssocDetail::industry));
        return new Result(100.0 * raw2, cap(details));
    }

    /** 名次归一：heat DESC、行业名 ASC 破并列 → heatNorm = (31 − rank) / 30；缺行行业缺席（取 0）。 */
    static Map<String, Double> heatNormByIndustry(List<HeatRow> h24Heat) {
        List<HeatRow> sorted = new ArrayList<>(h24Heat == null ? List.<HeatRow>of() : h24Heat);
        sorted.sort(
                Comparator.comparingDouble((HeatRow row) -> row.heatScore())
                        .reversed()
                        .thenComparing(HeatRow::industry));
        Map<String, Double> norms = new HashMap<>();
        for (int rank = 1; rank <= sorted.size(); rank++) {
            HeatRow row = sorted.get(rank - 1);
            double norm = (SW_INDUSTRY_ROWS - rank) / (double) (SW_INDUSTRY_ROWS - 1);
            norms.put(row.industry(), Math.max(0.0, norm));
        }
        return norms;
    }

    private static double heatScoreOf(List<HeatRow> h24Heat, String industry) {
        return h24Heat == null
                ? 0.0
                : h24Heat.stream()
                        .filter(row -> row.industry().equals(industry))
                        .findFirst()
                        .map(HeatRow::heatScore)
                        .orElse(0.0);
    }

    private static List<AssocDetail> cap(List<AssocDetail> details) {
        return List.copyOf(details.subList(0, Math.min(CatalystFactor.ENTRY_CAP, details.size())));
    }
}
