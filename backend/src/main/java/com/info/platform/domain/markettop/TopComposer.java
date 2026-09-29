package com.info.platform.domain.markettop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Top10 合成器（M21 T183，方案 §4.5.1 + ADR-0059 裁决 5，domain 纯函数）：diveScore 结构分派生 → final = max(总分, 0.8×总分
 * + 0.2×diveScore)（max 包络——深析只加分不惩罚）→ 四键排序 → 恰 10 截断。
 *
 * <ul>
 *   <li>diveScore = 25（thesis 合格）+ 10×min(#亮点,3) + 10×min(#风险,2) + 5×min(#有效引用,5)，合格区间
 *       [75,100]——全部从校验后 JSON 结构派生，同输入同输出可复算（dive_detail 即审计锚）；TEMPLATE
 *       兜底不参与（generation=FACTOR_ONLY）。
 *   <li>0.2 权重幅面 ≤5 分保证排序因子分主导（M22 防抖在 M21 即成立）；factor_only 标的 final = 总分，同序可比。
 *   <li>排序：final DESC → f_catalyst DESC → last_event_date DESC（NULL 最后）→ subject_id ASC；topSize=10
 *       代码常量（蓝图锁死）。
 * </ul>
 */
public final class TopComposer {

    /** 榜单容量（恰 10——代码常量不配置，蓝图锁死；合格 &lt; 10 如实呈现）。 */
    public static final int TOP_SIZE = 10;

    private TopComposer() {}

    /** 合成入参（粗筛池行 + 深析产物；dive=null → FACTOR_ONLY）。 */
    public record ScoredSubject(
            long subjectId,
            String subjectCode,
            String subjectName,
            double totalScore,
            double fCatalyst,
            String lastEventDate,
            boolean breakthrough,
            double percentile,
            int evidenceCount,
            DeepDiveOutcome dive) {}

    /** 合成产物（market_top_rank 行载荷；fCatalyst 为次级排序键携带；dive_detail JSON 序列化在持久化边界）。 */
    public record Composed(
            long subjectId,
            String subjectCode,
            String subjectName,
            double totalScore,
            double fCatalyst,
            double finalScore,
            double percentile,
            boolean breakthrough,
            String generation,
            String diveMethod,
            Double diveScore,
            DeepDiveOutcome dive,
            int evidenceCount,
            String lastEventDate) {}

    /** 合成配置快照（basis 口径串来源；值域校验归 MarketTopConfigValidator）。 */
    public record Config(int poolSize, int deepDiveLimit, double deepDiveCostCapRatio) {}

    /**
     * 深析结构分（§4.5.1）：25 + 10×min(亮点,3) + 10×min(风险,2) + 5×min(有效引用,5)。
     *
     * <p>仅对 LLM 终态有意义（TEMPLATE/factor_only 不参与）；有效引用 = citations 去重并集数（对账后全 ⊆ 白名单）。
     */
    public static double diveScore(DeepDiveOutcome dive) {
        int highlights = Math.min(dive.highlights().size(), 3);
        int risks = Math.min(dive.risks().size(), 2);
        int citations = Math.min(dive.citations().size(), 5);
        return 25 + 10 * highlights + 10 * risks + 5 * citations;
    }

    /** 合成主流程（确定性纯函数——同输入同榜单，幂等验收锚）。 */
    public static List<Composed> compose(List<ScoredSubject> subjects, Config config) {
        List<Composed> all = new ArrayList<>(subjects.size());
        for (ScoredSubject subject : subjects) {
            all.add(composeOne(subject));
        }
        all.sort(FINAL_ORDER);
        return List.copyOf(all.subList(0, Math.min(TOP_SIZE, all.size())));
    }

    private static Composed composeOne(ScoredSubject subject) {
        boolean fullDive =
                subject.dive() != null && subject.dive().method() == DeepDiveOutcome.GenMethod.LLM;
        if (!fullDive) {
            return new Composed(
                    subject.subjectId(),
                    subject.subjectCode(),
                    subject.subjectName(),
                    subject.totalScore(),
                    subject.fCatalyst(),
                    subject.totalScore(),
                    subject.percentile(),
                    subject.breakthrough(),
                    "FACTOR_ONLY",
                    subject.dive() == null ? null : subject.dive().method().name(),
                    null,
                    subject.dive(),
                    subject.evidenceCount(),
                    subject.lastEventDate());
        }
        double diveScore = diveScore(subject.dive());
        double finalScore =
                Math.max(subject.totalScore(), 0.8 * subject.totalScore() + 0.2 * diveScore);
        return new Composed(
                subject.subjectId(),
                subject.subjectCode(),
                subject.subjectName(),
                subject.totalScore(),
                subject.fCatalyst(),
                finalScore,
                subject.percentile(),
                subject.breakthrough(),
                "FULL",
                "LLM",
                diveScore,
                subject.dive(),
                subject.evidenceCount(),
                subject.lastEventDate());
    }

    /** basis 口径串（§4.5.1 冻结形态；配置值嵌入供版本对齐断言）。 */
    public static String basis(Config config) {
        return "mt-v1:final=max(total,0.8*total+0.2*dive);dive=25|10x3|10x2|5x5;"
                + "pool="
                + config.poolSize()
                + ";dive="
                + config.deepDiveLimit()
                + ";cap="
                + String.format(java.util.Locale.ROOT, "%.2f", config.deepDiveCostCapRatio());
    }

    /**
     * 港美股 basis 口径串（M29 T256，方案 §5.5/§7.2——「mt-v1:hkus」前缀 + 维度裁剪留痕）：final = 剩余维（F1/F2/F4）权重置 0 价值维后
     * 再归一（非简单低分）；深析 A 股先行（W1 价值维依赖，港美股零 LLM）；quotes = 该市场行情快照最近日（数据口径留痕——盘中未收敛照常 出榜）。
     *
     * @param weights 剩余维有效权重（w1|w2|w4——F3/F5 置 0 后的口径审计锚）
     * @param quotesAsOf 该市场行情快照最近日（无任何快照 = "none"）
     */
    public static String basisHkus(Config config, String weights, String quotesAsOf, int topSize) {
        return "mt-v1:hkus:final=renorm(F1,F2,F4);w="
                + weights
                + ";missing=fundamental|valuation;dive=A_SHARE_ONLY;pool="
                + config.poolSize()
                + ";top="
                + topSize
                + ";quotes="
                + (quotesAsOf == null || quotesAsOf.isBlank() ? "none" : quotesAsOf);
    }

    /**
     * final 全序（可复现）：final DESC → f_catalyst DESC → last_event_date DESC（NULL 最后）→ subject_id ASC
     * （NULL 最旧实现同 MarketTopPoolBuilder.FOUR_KEY_ORDER：nullsFirst 再整体 reversed）。
     */
    private static final Comparator<Composed> FINAL_ORDER =
            Comparator.comparingDouble(Composed::finalScore)
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(Composed::fCatalyst).reversed())
                    .thenComparing(
                            Comparator.comparing(
                                            Composed::lastEventDate,
                                            Comparator.nullsFirst(Comparator.naturalOrder()))
                                    .reversed())
                    .thenComparingLong(Composed::subjectId);
}
