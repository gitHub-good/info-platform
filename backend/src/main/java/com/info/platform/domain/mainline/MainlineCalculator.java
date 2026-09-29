package com.info.platform.domain.mainline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主线榜单计算纯函数 mainline-v1（M27 T243，方案 §3.4 + ADR-0063 裁决 4）：三维行业内百分位加权合成 + 持续性硬门槛 → Top N。
 *
 * <pre>
 * P(I) 价格动量 = pw_day·pctile(pct_day) + pw_d5·pctile(pct_d5)          # 31 行业内百分位
 * H(I) 资讯热度 = h24·pctile(heat_H24) + d7·pctile(heat_D7) + delta·pctile(delta_pct)
 * E(I) 事件密度 = pctile(Σ 近5交易日 事件加权 HIGH×2/MEDIUM×1)
 * M(I) 主线分   = wp·P + wh·H + we·E                                      # 权重可配（三项和 = 1）
 * 门槛：近 persistWindowDays 交易日中 ≥ persistMinDays 日
 *       [ 当日 pct_day 降序名次 ≤ topThirdRank ] ∨ [ 当日热度降序名次 ≤ 10 ]   —— 硬门槛非计分（ADR-0063 裁决 4）
 * 输出：门槛通过者按 M 降序（并列行业名升序）取 Top N；某维原始值全缺 → 该维 50 中性 + dimensionMissing 留痕
 * </pre>
 *
 * <p>纯函数零 IO 零 LLM：同输入重算逐字节相等（幂等复算构造性保证，沿 M20 MainlineCalculator 先例）。
 */
public final class MainlineCalculator {

    /** 热度侧持续性名次上限（当日 heat H24 降序名次 ≤ 10 计一天，方案 §3.4 定值）。 */
    static final int HEAT_TOP_RANK_LIMIT = 10;

    private MainlineCalculator() {}

    /** 计算参数（{@code industry.mainline} 配置键的类型化形态，权重可热调）。 */
    public record Params(
            double wp,
            double wh,
            double we,
            double priceWinDay,
            double priceWinD5,
            double heatH24,
            double heatD7,
            double heatDelta,
            int topN,
            int persistMinDays,
            int persistWindowDays,
            int topThirdRank,
            int divergenceHeatRank) {}

    /** 单行业当日输入（原始值，null = 缺数）。 */
    public record IndustryRow(
            String industry,
            Double pctDay,
            Double pctD5,
            Double heatH24,
            Double heatD7,
            Double deltaPct,
            Double eventWeighted) {}

    /**
     * 计算输入。
     *
     * @param current 当日 31 行业原始值
     * @param dailyPctDay 窗口各交易日 industry→pct_day（时间升序、末位 = 当日——价格侧持续性原料）
     * @param dailyHeat 窗口各交易日 industry→heatScore（时间升序、末位 = 当日 H24 现值；历史取自日报 heat_top）
     */
    public record CalculationInput(
            List<IndustryRow> current,
            List<Map<String, Double>> dailyPctDay,
            List<Map<String, Double>> dailyHeat) {}

    /** 三维分解（各维 rank/score/raw——dim_detail JSON 契约形态）。 */
    public record DimDetail(double score, int rank, Double raw) {}

    /** 单行业输出行（门槛后 Top N 元素）。 */
    public record MainlineRow(
            String industry,
            double mainScore,
            DimDetail price,
            DimDetail heat,
            DimDetail event,
            int persistentDays,
            Integer heatRank,
            String divergence) {}

    /** 计算结果（Top N 行 + 缺维留痕 + 门槛通过数 + 冷启动标记）。 */
    public record Result(
            List<MainlineRow> topRows,
            int gatePassed,
            Map<String, Boolean> dimensionMissing,
            boolean bootstrap) {}

    /**
     * 计算 Top N 主线行。
     *
     * @param params 权重/门槛参数
     * @param input 三路输入
     * @return 门槛过滤后 M 降序 Top N（不足如实输出实有条数，不出空榜单占位）；dimensionMissing = 缺维留痕
     */
    public static Result calculate(Params params, CalculationInput input) {
        List<IndustryRow> rows = input.current();
        Map<String, Double> pctDay = byIndustry(rows, IndustryRow::pctDay);
        Map<String, Double> pctD5 = byIndustry(rows, IndustryRow::pctD5);
        Map<String, Double> heatH24 = byIndustry(rows, IndustryRow::heatH24);
        Map<String, Double> heatD7 = byIndustry(rows, IndustryRow::heatD7);
        Map<String, Double> deltaPct = byIndustry(rows, IndustryRow::deltaPct);
        Map<String, Double> eventWeighted = byIndustry(rows, IndustryRow::eventWeighted);

        Map<String, Boolean> missing = new LinkedHashMap<>();
        boolean pctD5Missing = allNull(pctD5);
        boolean heatMissing = allNull(heatH24) && allNull(heatD7) && allNull(deltaPct);
        boolean eventMissing = allNull(eventWeighted);
        missing.put("pctD5", pctD5Missing);
        missing.put("heat", heatMissing);
        missing.put("event", eventMissing);

        Map<String, Double> pctileDay = Percentiles.of(pctDay);
        Map<String, Double> pctileD5 = pctD5Missing ? neutralAll(pctD5) : Percentiles.of(pctD5);
        Map<String, Double> pctileH24 = heatMissing ? neutralAll(heatH24) : Percentiles.of(heatH24);
        Map<String, Double> pctileD7 = heatMissing ? neutralAll(heatD7) : Percentiles.of(heatD7);
        Map<String, Double> pctileDelta =
                heatMissing ? neutralAll(deltaPct) : Percentiles.of(deltaPct);
        Map<String, Double> pctileEvent =
                eventMissing ? neutralAll(eventWeighted) : Percentiles.of(eventWeighted);

        Map<String, Double> priceScore = new LinkedHashMap<>();
        Map<String, Double> heatScore = new LinkedHashMap<>();
        Map<String, Double> eventScore = new LinkedHashMap<>();
        for (IndustryRow row : rows) {
            String industry = row.industry();
            priceScore.put(
                    industry,
                    params.priceWinDay() * pctileDay.get(industry)
                            + params.priceWinD5() * pctileD5.get(industry));
            heatScore.put(
                    industry,
                    params.heatH24() * pctileH24.get(industry)
                            + params.heatD7() * pctileD7.get(industry)
                            + params.heatDelta() * pctileDelta.get(industry));
            eventScore.put(industry, pctileEvent.get(industry));
        }
        Map<String, Integer> priceRank = Percentiles.rankDesc(priceScore);
        Map<String, Integer> heatDimRank = Percentiles.rankDesc(heatScore);
        Map<String, Integer> eventDimRank = Percentiles.rankDesc(eventScore);
        Map<String, Integer> currentHeatRank = Percentiles.rankDesc(heatH24);

        Map<String, Integer> persistentDays = persistentDays(input, params);
        Map<String, Integer> currentPriceRank = Percentiles.rankDesc(pctDay);

        List<MainlineRow> candidates = new ArrayList<>();
        for (IndustryRow row : rows) {
            String industry = row.industry();
            int persist = persistentDays.getOrDefault(industry, 0);
            if (persist < params.persistMinDays()) {
                continue;
            }
            double mainScore =
                    params.wp() * priceScore.get(industry)
                            + params.wh() * heatScore.get(industry)
                            + params.we() * eventScore.get(industry);
            Integer heatRank = currentHeatRank.get(industry);
            Integer dayRank = currentPriceRank.get(industry);
            String divergence =
                    dayRank != null
                                    && dayRank <= 3
                                    && heatRank != null
                                    && heatRank > params.divergenceHeatRank()
                            ? "PRICE_HOT_HEAT_COLD"
                            : "NONE";
            candidates.add(
                    new MainlineRow(
                            industry,
                            mainScore,
                            new DimDetail(
                                    priceScore.get(industry),
                                    priceRank.get(industry),
                                    row.pctDay()),
                            new DimDetail(
                                    heatScore.get(industry),
                                    heatDimRank.get(industry),
                                    row.heatH24()),
                            new DimDetail(
                                    eventScore.get(industry),
                                    eventDimRank.get(industry),
                                    row.eventWeighted()),
                            persist,
                            heatRank,
                            divergence));
        }
        candidates.sort(
                java.util.Comparator.comparing(MainlineRow::mainScore)
                        .reversed()
                        .thenComparing(MainlineRow::industry));
        int gatePassed = candidates.size();
        // 冷启动兜底：可用历史天数 < persistMinDays 时门槛数学上不可能通过（max persist =
        // 可用天数），此时按 mainScore 免门槛出榜并标记 bootstrap——行内 persistentDays 如实
        // 展示真实持续性，待历史攒够自动恢复严格门槛口径
        int availableDays =
                Math.max(
                        (int) input.dailyPctDay().stream().filter(m -> !m.isEmpty()).count(),
                        (int) input.dailyHeat().stream().filter(m -> !m.isEmpty()).count());
        boolean bootstrap =
                candidates.isEmpty() && !rows.isEmpty() && availableDays < params.persistMinDays();
        if (bootstrap) {
            for (IndustryRow row : rows) {
                String industry = row.industry();
                double mainScore =
                        params.wp() * priceScore.get(industry)
                                + params.wh() * heatScore.get(industry)
                                + params.we() * eventScore.get(industry);
                Integer heatRank = currentHeatRank.get(industry);
                Integer dayRank = currentPriceRank.get(industry);
                String divergence =
                        dayRank != null
                                        && dayRank <= 3
                                        && heatRank != null
                                        && heatRank > params.divergenceHeatRank()
                                ? "PRICE_HOT_HEAT_COLD"
                                : "NONE";
                candidates.add(
                        new MainlineRow(
                                industry,
                                mainScore,
                                new DimDetail(
                                        priceScore.get(industry),
                                        priceRank.get(industry),
                                        row.pctDay()),
                                new DimDetail(
                                        heatScore.get(industry),
                                        heatDimRank.get(industry),
                                        row.heatH24()),
                                new DimDetail(
                                        eventScore.get(industry),
                                        eventDimRank.get(industry),
                                        row.eventWeighted()),
                                persistentDays.getOrDefault(industry, 0),
                                heatRank,
                                divergence));
            }
            candidates.sort(
                    java.util.Comparator.comparing(MainlineRow::mainScore)
                            .reversed()
                            .thenComparing(MainlineRow::industry));
        }
        List<MainlineRow> top = candidates.stream().limit(params.topN()).toList();
        return new Result(List.copyOf(top), gatePassed, Map.copyOf(missing), bootstrap);
    }

    /**
     * 持续性计数：窗口各交易日 [pct_day 名次 ≤ topThirdRank] ∨ [heat 名次 ≤ 10] 的天数。 两路窗口按日对齐等长（缺数日 = 空映射
     * 占位）：某日某路缺数则该路当日不判过，另一路照常——价格侧历史（本表日行）与热度侧历史（日报 heat_top）独立成天数。
     */
    private static Map<String, Integer> persistentDays(CalculationInput input, Params params) {
        Map<String, Integer> days = new LinkedHashMap<>();
        List<String> industries = input.current().stream().map(IndustryRow::industry).toList();
        for (String industry : industries) {
            days.put(industry, 0);
        }
        int window =
                Math.min(
                        params.persistWindowDays(),
                        Math.min(input.dailyPctDay().size(), input.dailyHeat().size()));
        for (int i = 0; i < window; i++) {
            Map<String, Double> pctDay = input.dailyPctDay().get(i);
            Map<String, Double> heatDay = input.dailyHeat().get(i);
            Map<String, Integer> priceRank =
                    pctDay.isEmpty() ? Map.of() : Percentiles.rankDesc(pctDay);
            Map<String, Integer> heatRank =
                    heatDay.isEmpty() ? Map.of() : Percentiles.rankDesc(heatDay);
            for (String industry : industries) {
                Integer rank = priceRank.get(industry);
                Integer heat = heatRank.get(industry);
                boolean pricePass = rank != null && rank <= params.topThirdRank();
                boolean heatPass = heat != null && heat <= HEAT_TOP_RANK_LIMIT;
                if (pricePass || heatPass) {
                    days.merge(industry, 1, Integer::sum);
                }
            }
        }
        return days;
    }

    private static Map<String, Double> byIndustry(
            List<IndustryRow> rows, java.util.function.Function<IndustryRow, Double> extractor) {
        Map<String, Double> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(row.industry(), extractor.apply(row)));
        return map;
    }

    private static boolean allNull(Map<String, Double> values) {
        return values.values().stream().allMatch(Objects::isNull);
    }

    private static Map<String, Double> neutralAll(Map<String, Double> values) {
        Map<String, Double> map = new LinkedHashMap<>();
        values.keySet().forEach(key -> map.put(key, Percentiles.NEUTRAL));
        return map;
    }
}
