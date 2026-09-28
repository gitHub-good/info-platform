package com.info.platform.domain.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.mainline.MainlineCalculator.CalculationInput;
import com.info.platform.domain.mainline.MainlineCalculator.DimDetail;
import com.info.platform.domain.mainline.MainlineCalculator.IndustryRow;
import com.info.platform.domain.mainline.MainlineCalculator.MainlineRow;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import com.info.platform.domain.mainline.MainlineCalculator.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MainlineCalculator 纯函数单测（M27 T243，方案 §3.4 + ADR-0063 裁决 4——§6 测试要点）：三维百分位矩阵 / 持续性硬门槛（单日脉冲不进
 * Top3）/ 缺维中性 50 + 留痕 / 权重热调改变合成 / 背离标注 / 同输入重算零漂移（幂等构造性）。
 */
class MainlineCalculatorTest {

    private static final Params DEFAULT_PARAMS =
            new Params(0.40, 0.35, 0.25, 0.5, 0.5, 0.5, 0.3, 0.2, 5, 2, 5, 10, 13);

    private static IndustryRow row(
            String industry,
            Double pctDay,
            Double pctD5,
            Double h24,
            Double d7,
            Double delta,
            Double events) {
        return new IndustryRow(industry, pctDay, pctD5, h24, d7, delta, events);
    }

    /** 5 行业微型矩阵（n=5：最高值百分位 100、最低 0）。 */
    private static List<IndustryRow> fiveIndustries() {
        return List.of(
                row("甲", 3.0, 6.0, 90d, 80d, 50d, 6d),
                row("乙", 2.0, 4.0, 70d, 60d, 30d, 4d),
                row("丙", 1.0, 2.0, 50d, 40d, 10d, 2d),
                row("丁", 0.0, 0.0, 30d, 20d, -10d, 0d),
                row("戊", -1.0, -2.0, 10d, 5d, -30d, 0d));
    }

    /** 窗口日序列：industry→值（5 日全同一形态 = 持续性行业全覆盖）。 */
    private static List<Map<String, Double>> uniformWindow(List<IndustryRow> rows) {
        Map<String, Double> day = new java.util.LinkedHashMap<>();
        rows.forEach(r -> day.put(r.industry(), r.pctDay()));
        return List.of(
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day));
    }

    private static List<Map<String, Double>> heatWindowOf(List<IndustryRow> rows) {
        Map<String, Double> day = new java.util.LinkedHashMap<>();
        rows.forEach(r -> day.put(r.industry(), r.heatH24()));
        return List.of(
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day),
                Map.copyOf(day));
    }

    @Test
    void calculate_percentileMatrix_composesThreeDimensions() {
        List<IndustryRow> rows = fiveIndustries();

        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS,
                        new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows)));

        // 甲三维全顶（100）→ M = 100；门槛 5/5 通过；Top1 = 甲
        assertThat(result.topRows()).hasSize(5);
        MainlineRow top = result.topRows().get(0);
        assertThat(top.industry()).isEqualTo("甲");
        assertThat(top.mainScore()).isCloseTo(100d, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(top.price().rank()).isEqualTo(1);
        assertThat(top.heat().rank()).isEqualTo(1);
        assertThat(top.event().rank()).isEqualTo(1);
        assertThat(top.persistentDays()).isEqualTo(5);
        assertThat(top.heatRank()).isEqualTo(1);
        assertThat(top.divergence()).isEqualTo("NONE");
        // 戊全底 → M = 0
        MainlineRow bottom = result.topRows().get(4);
        assertThat(bottom.industry()).isEqualTo("戊");
        assertThat(bottom.mainScore()).isCloseTo(0d, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.dimensionMissing())
                .containsEntry("pctD5", false)
                .containsEntry("heat", false)
                .containsEntry("event", false);
    }

    @Test
    void calculate_singleDaySpike_gateFiltersOutOfTop() {
        // 12 行业矩阵（HEAT_TOP_RANK_LIMIT=10 / topThirdRank=10 在 n=12 下有区分度）：
        // 「甲」仅当日价格第一，前 4 日价格垫底（名次 12 > 10）且热度常年垫底（名次 12 > 10）→ 持续性 1 < 2 → 不进榜
        List<IndustryRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(
                    row(
                            i == 0 ? "甲" : "行" + String.format("%02d", i),
                            i == 0 ? -5d : 12d - i,
                            i == 0 ? -5d : 12d - i,
                            i == 0 ? 1d : (12 - i) * 10d,
                            i == 0 ? 1d : (12 - i) * 5d,
                            0d,
                            i == 0 ? 6d : (12 - i) * 1d));
        }
        Map<String, Double> spikeDay = new java.util.LinkedHashMap<>();
        Map<String, Double> normalDay = new java.util.LinkedHashMap<>();
        rows.forEach(
                r -> {
                    spikeDay.put(r.industry(), r.industry().equals("甲") ? 5d : 1d);
                    normalDay.put(r.industry(), r.industry().equals("甲") ? -5d : 1d);
                });
        List<Map<String, Double>> window =
                List.of(
                        Map.copyOf(normalDay),
                        Map.copyOf(normalDay),
                        Map.copyOf(normalDay),
                        Map.copyOf(normalDay),
                        Map.copyOf(spikeDay));
        List<Map<String, Double>> heatWindow = heatWindowOf(rows);

        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS, new CalculationInput(rows, window, heatWindow));

        assertThat(result.topRows()).noneMatch(row -> row.industry().equals("甲"));
        // 其余 11 行价格并列名次 1（≤10）每日过 → 5/5
        assertThat(result.gatePassed()).isEqualTo(11);
    }

    @Test
    void calculate_pctD5AllMissing_dimensionNeutralWithFlag() {
        // 冷启动：pct_d5 全 NULL → 价格子维 d5 记 50 中性 + dimensionMissing.pctD5 = true（不出空榜）
        List<IndustryRow> rows =
                List.of(
                        row("甲", 3.0, null, 90d, 80d, 50d, 6d),
                        row("乙", 2.0, null, 70d, 60d, 30d, 4d),
                        row("丙", 1.0, null, 50d, 40d, 10d, 2d),
                        row("丁", 0.0, null, 30d, 20d, -10d, 0d),
                        row("戊", -1.0, null, 10d, 5d, -30d, 0d));

        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS,
                        new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows)));

        assertThat(result.dimensionMissing()).containsEntry("pctD5", true);
        assertThat(result.topRows()).isNotEmpty();
        // 甲：价格 = 0.5×100 + 0.5×50 = 75（d5 中性拉半）；热度/事件全 100 → M = 0.4×75 + 0.35×100 + 0.25×100 = 90
        assertThat(result.topRows().get(0).mainScore())
                .isCloseTo(90d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void calculate_heatSideMissing_heatDimensionNeutral() {
        List<IndustryRow> rows =
                List.of(
                        row("甲", 3.0, 6.0, null, null, null, 6d),
                        row("乙", 2.0, 4.0, null, null, null, 4d),
                        row("丙", 1.0, 2.0, null, null, null, 2d),
                        row("丁", 0.0, 0.0, null, null, null, 0d),
                        row("戊", -1.0, -2.0, null, null, null, 0d));

        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS,
                        new CalculationInput(
                                rows,
                                uniformWindow(rows),
                                List.of(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));

        assertThat(result.dimensionMissing()).containsEntry("heat", true);
        // 甲：价格 100 + 热度 50 中性 + 事件 100 → M = 40 + 17.5 + 25 = 82.5
        assertThat(result.topRows().get(0).mainScore())
                .isCloseTo(82.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void calculate_weightHotAdjust_changesComposition() {
        // 权重热调（n=2 矩阵，百分位顶 100/底 0）：甲 = 事件顶+价格底，乙 = 价格顶+事件底，热度同权中性
        List<IndustryRow> rows =
                List.of(
                        row("甲", -1.0, -2.0, 50d, 50d, 0d, 6d),
                        row("乙", 3.0, 6.0, 50d, 50d, 0d, 0d));
        Params eventHeavy = new Params(0.20, 0.25, 0.55, 0.5, 0.5, 0.5, 0.3, 0.2, 5, 2, 5, 10, 13);
        Params priceHeavy = new Params(0.70, 0.20, 0.10, 0.5, 0.5, 0.5, 0.3, 0.2, 5, 2, 5, 10, 13);

        Result eventResult =
                MainlineCalculator.calculate(
                        eventHeavy,
                        new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows)));
        Result priceResult =
                MainlineCalculator.calculate(
                        priceHeavy,
                        new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows)));

        // 事件重（0.55）：甲 = 0.2×0 + 0.55×100 = 55（热度两行同值并列 → 百分位 0，均匀不改变排序）
        assertThat(eventResult.topRows().get(0).industry()).isEqualTo("甲");
        assertThat(eventResult.topRows().get(0).mainScore())
                .isCloseTo(55d, org.assertj.core.data.Offset.offset(1e-9));
        // 价格重（0.70）：乙 = 0.7×100 = 70 > 甲 = 0.1×100 = 10
        assertThat(priceResult.topRows().get(0).industry()).isEqualTo("乙");
        assertThat(priceResult.topRows().get(0).mainScore())
                .isCloseTo(70d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void calculate_divergence_priceHotHeatColdFlagged() {
        // 价格第 1（≤3）且热度第 4（>13？）——微型矩阵 n=5 时热度名次 4 ≤ 13 不触发；构造 15 行业矩阵验证
        List<IndustryRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            // 价格：甲第一；热度：甲垫底（名次 15 > 13）
            rows.add(
                    row(
                            i == 0 ? "甲" : "行" + i,
                            i == 0 ? 5d : 4d - i * 0.1,
                            i == 0 ? 5d : 4d - i * 0.1,
                            i == 0 ? 1d : (14d - i) * 10,
                            i == 0 ? 1d : (14d - i) * 5,
                            i == 0 ? 0d : (14d - i),
                            i == 0 ? 6d : (14d - i)));
        }
        List<Map<String, Double>> pctWindow = uniformWindow(rows);
        List<Map<String, Double>> heatWindow = heatWindowOf(rows);

        Result result =
                MainlineCalculator.calculate(
                        new Params(0.40, 0.35, 0.25, 0.5, 0.5, 0.5, 0.3, 0.2, 15, 2, 5, 10, 13),
                        new CalculationInput(rows, pctWindow, heatWindow));

        MainlineRow jia =
                result.topRows().stream()
                        .filter(r -> r.industry().equals("甲"))
                        .findFirst()
                        .orElseThrow();
        assertThat(jia.divergence()).isEqualTo("PRICE_HOT_HEAT_COLD");
        assertThat(jia.heatRank()).isEqualTo(14); // 13 行 heat 130..0 严格大于 1 → 名次 14 > 13
    }

    @Test
    void calculate_topNFewerThanConfig_outputsActualCount() {
        // 门槛不足 → 如实输出实有条数（不出空榜单占位）
        List<IndustryRow> rows = fiveIndustries();
        Map<String, Double> cold = new java.util.LinkedHashMap<>();
        rows.forEach(r -> cold.put(r.industry(), 0d));

        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS,
                        new CalculationInput(
                                rows,
                                List.of(
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold)),
                                heatWindowOf(rows)));

        // 价格全 0 并列名次 1（≤10）→ 全过门槛；热度窗 5 日有高有低
        assertThat(result.topRows()).hasSize(5);
        Result strictGate =
                MainlineCalculator.calculate(
                        new Params(0.40, 0.35, 0.25, 0.5, 0.5, 0.5, 0.3, 0.2, 5, 5, 5, 1, 13),
                        new CalculationInput(
                                rows,
                                List.of(
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold),
                                        Map.copyOf(cold)),
                                heatWindowOf(rows)));
        // topThirdRank=1 且价格全并列名次 1 → 全过；再抬 persistMinDays=5 需每日过 → 仅价格并列 1 满足
        assertThat(strictGate.topRows()).isNotEmpty();
    }

    @Test
    void calculate_sameInputTwice_byteIdentical_zeroDrift() {
        List<IndustryRow> rows = fiveIndustries();
        CalculationInput input =
                new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows));

        Result first = MainlineCalculator.calculate(DEFAULT_PARAMS, input);
        Result second = MainlineCalculator.calculate(DEFAULT_PARAMS, input);

        assertThat(first).isEqualTo(second);
        assertThat(first.topRows().toString()).isEqualTo(second.topRows().toString());
    }

    @Test
    void calculate_tiesShareRank_deterministic() {
        // 并列原始值 → 同百分位同名次（取小秩），输出序按 M 降序 + 行业名升序确定性
        List<IndustryRow> rows =
                List.of(
                        row("甲", 1.0, 1.0, 50d, 50d, 0d, 2d),
                        row("乙", 1.0, 1.0, 50d, 50d, 0d, 2d),
                        row("丙", 2.0, 2.0, 80d, 80d, 20d, 4d));
        Result result =
                MainlineCalculator.calculate(
                        DEFAULT_PARAMS,
                        new CalculationInput(rows, uniformWindow(rows), heatWindowOf(rows)));

        DimDetail firstPrice = result.topRows().get(0).price();
        List<MainlineRow> tail = result.topRows().subList(1, 3);
        // 并列行业按代码点升序（乙 U+4E59 < 甲 U+7532）——确定性序
        assertThat(tail.get(0).industry()).isEqualTo("乙");
        assertThat(tail.get(1).industry()).isEqualTo("甲");
        assertThat(tail.get(0).mainScore()).isEqualTo(tail.get(1).mainScore());
        assertThat(firstPrice.rank()).isEqualTo(1);
    }
}
