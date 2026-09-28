package com.info.platform.domain.mainline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 龙头识别纯函数 leader-v1（M27 T244，方案 §3.5 + ADR-0063 裁决 5）：主线行业成员内三维综合分，行业成员集内百分位加权。
 *
 * <pre>
 * A(S) 资讯关注度(主维) = 近 mentionDays 日 mentions + 事件加权（HIGH×2/MEDIUM×1）——原始计数
 * V(S) 价值评分 = subject_factor_snapshot.total_score（缺失 → 50 中性 + flag）
 * Q(S) 价格动量 = qDay·pctile(pct_day) + qD5·pctile(pct_d5)（market_daily_snapshot 库内两窗自算；停牌/无行 → 50 + flag）
 * L(S) 龙头分 = wa·pctile_I(A) + wv·pctile_I(V) + wq·pctile_I(Q)   # 行业内百分位，权重可配
 * 输出：Top N（默认 3）= 龙一/二/三 + 三维分解 + 依据回溯字段（mentions/事件 id/快照日——组装 JSON 归应用层）
 * </pre>
 *
 * <p>候选过滤（ST 排除留痕/停牌保留）在服务装载层完成——本类只做纯计算；同输入重算逐字节相等（幂等构造性，沿 M20 先例）。
 */
public final class LeaderCalculator {

    private LeaderCalculator() {}

    /** 龙头参数（{@code industry.leader} 键的类型化形态）。 */
    public record Params(
            double wa, double wv, double wq, int mentionDays, int topN, double qDay, double qD5) {}

    /**
     * 候选行（已过 ST 排除的成员——excluded.st 留痕在服务层 funnel）。
     *
     * @param valueScore M20 total_score（null = 无快照 → 价值维 50 中性）
     * @param eventWeighted 近窗事件加权和（HIGH×2/MEDIUM×1，与 mentions 相加构成 A 原始值）
     * @param eventCount 近窗事件计数（关联事件条数——dim JSON 契约字段）
     * @param riskEvents 近窗利空（BEARISH）事件计数（basis 留痕——风险不静默）
     * @param dataFlagsJson M20 data_flags JSON 透传（null = 无快照）
     */
    public record Candidate(
            long subjectId,
            String subjectCode,
            String subjectName,
            int mentions,
            double eventWeighted,
            int eventCount,
            int riskEvents,
            Double valueScore,
            String dataFlagsJson,
            Double pctDay,
            Double pctD5) {}

    /** 三维分解（rank/score/raw + 各维缺数 flag——leaders JSON 契约形态）。 */
    public record LeaderDim(int rank, double score, Double raw, String flag) {}

    /** 龙头输出行（依据回溯原始值随行——§4.4.3 dim JSON 组装归应用层）。 */
    public record LeaderRow(
            int rank,
            long subjectId,
            String subjectCode,
            String subjectName,
            double score,
            LeaderDim attention,
            LeaderDim value,
            LeaderDim price,
            int mentions,
            int eventCount,
            double eventWeighted,
            Double pctDay,
            Double pctD5,
            String dataFlagsJson) {}

    /** 计算结果（Top N + 缺维留痕 + 排除计数由服务层并入 funnel）。 */
    public record Result(List<LeaderRow> leaders, Map<String, Boolean> dimensionMissing) {}

    /**
     * 计算行业内龙头 Top N。
     *
     * @param params 权重/窗口参数
     * @param candidates 候选成员（ST 已排除；空列表返回空榜——如实输出）
     */
    public static Result calculate(Params params, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return new Result(
                    List.of(), Map.of("attention", false, "value", false, "price", false));
        }
        Map<String, Double> attentionRaw = new LinkedHashMap<>();
        Map<String, Double> valueRaw = new LinkedHashMap<>();
        Map<String, Double> pctDay = new LinkedHashMap<>();
        Map<String, Double> pctD5 = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String code = candidate.subjectCode();
            attentionRaw.put(code, (double) candidate.mentions() + candidate.eventWeighted());
            valueRaw.put(code, candidate.valueScore());
            pctDay.put(code, candidate.pctDay());
            pctD5.put(code, candidate.pctD5());
        }
        boolean valueMissing = valueRaw.values().stream().allMatch(java.util.Objects::isNull);
        boolean priceMissing =
                pctDay.values().stream().allMatch(java.util.Objects::isNull)
                        && pctD5.values().stream().allMatch(java.util.Objects::isNull);
        Map<String, Boolean> missing =
                Map.of("attention", false, "value", valueMissing, "price", priceMissing);

        Map<String, Double> pctileAttention = Percentiles.of(attentionRaw);
        Map<String, Double> pctileValue =
                valueMissing ? neutralAll(valueRaw) : Percentiles.of(valueRaw);
        Map<String, Double> pctilePctDay =
                priceMissing ? neutralAll(pctDay) : Percentiles.of(pctDay);
        Map<String, Double> pctilePctD5 = priceMissing ? neutralAll(pctD5) : Percentiles.of(pctD5);

        Map<String, Double> attentionScore = new LinkedHashMap<>();
        Map<String, Double> valueScore = new LinkedHashMap<>();
        Map<String, Double> priceScore = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String code = candidate.subjectCode();
            attentionScore.put(code, pctileAttention.get(code));
            valueScore.put(code, pctileValue.get(code));
            priceScore.put(
                    code,
                    params.qDay() * pctilePctDay.get(code) + params.qD5() * pctilePctD5.get(code));
        }
        Map<String, Integer> attentionRank = Percentiles.rankDesc(attentionScore);
        Map<String, Integer> valueRank = Percentiles.rankDesc(valueScore);
        Map<String, Integer> priceRank = Percentiles.rankDesc(priceScore);

        List<LeaderRow> rows = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            String code = candidate.subjectCode();
            double score =
                    params.wa() * attentionScore.get(code)
                            + params.wv() * valueScore.get(code)
                            + params.wq() * priceScore.get(code);
            rows.add(
                    new LeaderRow(
                            0,
                            candidate.subjectId(),
                            code,
                            candidate.subjectName(),
                            score,
                            new LeaderDim(
                                    attentionRank.get(code),
                                    attentionScore.get(code),
                                    attentionRaw.get(code),
                                    null),
                            new LeaderDim(
                                    valueRank.get(code),
                                    valueScore.get(code),
                                    candidate.valueScore(),
                                    candidate.valueScore() == null
                                            ? (valueMissing
                                                    ? "DIMENSION_MISSING"
                                                    : "NO_FACTOR_SNAPSHOT")
                                            : null),
                            new LeaderDim(
                                    priceRank.get(code),
                                    priceScore.get(code),
                                    candidate.pctDay(),
                                    candidate.pctDay() == null && candidate.pctD5() == null
                                            ? "NO_MARKET_DATA"
                                            : null),
                            candidate.mentions(),
                            candidate.eventCount(),
                            candidate.eventWeighted(),
                            candidate.pctDay(),
                            candidate.pctD5(),
                            candidate.dataFlagsJson()));
        }
        rows.sort(
                java.util.Comparator.comparing(LeaderRow::score)
                        .reversed()
                        .thenComparing(LeaderRow::subjectCode));
        List<LeaderRow> top = new ArrayList<>(Math.min(params.topN(), rows.size()));
        for (int i = 0; i < rows.size() && top.size() < params.topN(); i++) {
            LeaderRow row = rows.get(i);
            top.add(
                    new LeaderRow(
                            i + 1,
                            row.subjectId(),
                            row.subjectCode(),
                            row.subjectName(),
                            row.score(),
                            row.attention(),
                            row.value(),
                            row.price(),
                            row.mentions(),
                            row.eventCount(),
                            row.eventWeighted(),
                            row.pctDay(),
                            row.pctD5(),
                            row.dataFlagsJson()));
        }
        return new Result(List.copyOf(top), missing);
    }

    private static Map<String, Double> neutralAll(Map<String, Double> values) {
        Map<String, Double> map = new LinkedHashMap<>();
        values.keySet().forEach(key -> map.put(key, Percentiles.NEUTRAL));
        return map;
    }
}
