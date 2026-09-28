package com.info.platform.domain.mainline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 板块 → 申万行业聚合纯函数（M27 T242，方案 §4.2.2 + ADR-0063 裁决 2）：市值加权主口径 / 权重缺失 &gt;20% 回退等权 / 涨跌家数与主力净流入加法直聚。
 *
 * <p>口径：{@code pct_day(I) = Σ_B(w_B·pct_day(B)) / Σ w_B}，权重 w_B = 板块总市值 f20（&gt;0）。回退链：某行业权重缺失/≤0
 * 行占比 &gt;20% → 该行业当轮等权（{@code agg_method=EQUAL}）；行业下全部板块行缺席 → 该行业行缺席（快照缺口由通道 B 补或如实缺席）。 pct_day 为
 * null 的板块行不进涨跌幅平均（分子分母同除；加法列 null 记 0）。
 *
 * <p>确定性：行业序 = 板块行首现序（上游按 fid=f12 稳定排序 → 同输入聚合结果逐字节稳定，幂等复算零漂移）。
 */
public final class BoardAggregator {

    /** 权重缺失行占比回退阈值（&gt;20% 该行业当轮等权，方案 §3.2）。 */
    static final double MISSING_WEIGHT_FALLBACK_RATIO = 0.20;

    private BoardAggregator() {}

    /**
     * 聚合板块行为行业行。
     *
     * @param boards 已过滤未收录的板块行（industry 恒非空；空输入返回空）
     * @return 每个有板块的行业一行（aggMethod = CAP_WEIGHTED / EQUAL）
     */
    public static List<IndustryQuote> aggregate(List<BoardQuote> boards) {
        Map<String, List<BoardQuote>> byIndustry = new LinkedHashMap<>();
        for (BoardQuote board : boards) {
            byIndustry.computeIfAbsent(board.industry(), key -> new ArrayList<>()).add(board);
        }
        List<IndustryQuote> industries = new ArrayList<>(byIndustry.size());
        for (Map.Entry<String, List<BoardQuote>> entry : byIndustry.entrySet()) {
            industries.add(aggregateIndustry(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(industries);
    }

    /** 单行业聚合：加权/等权判定 + 加法列直聚（null 记 0）。 */
    private static IndustryQuote aggregateIndustry(String industry, List<BoardQuote> boards) {
        boolean equalWeight = useEqualWeight(boards);
        double pctSum = 0d;
        double weightSum = 0d;
        long up = 0L;
        long down = 0L;
        double flow = 0d;
        double marketValue = 0d;
        for (BoardQuote board : boards) {
            if (board.pctDay() != null) {
                double weight = equalWeight ? 1d : weightOf(board);
                pctSum += weight * board.pctDay();
                weightSum += weight;
            }
            up += board.upCount() == null ? 0 : board.upCount();
            down += board.downCount() == null ? 0 : board.downCount();
            flow += board.mainNetFlow() == null ? 0d : board.mainNetFlow();
            // 市值加法列：缺失/≤0（无效权重行）记 0 不计负（无效值不应拉低行业总市值）
            marketValue += board.totalMv() == null || board.totalMv() <= 0 ? 0d : board.totalMv();
        }
        Double pctDay = weightSum > 0 ? pctSum / weightSum : null;
        return new IndustryQuote(
                industry,
                pctDay,
                null,
                up > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) up,
                down > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) down,
                flow,
                marketValue,
                null,
                equalWeight ? "EQUAL" : "CAP_WEIGHTED");
    }

    /** 等权回退判定：有效权重行（f20 缺失/≤0）占比 &gt;20%。 */
    private static boolean useEqualWeight(List<BoardQuote> boards) {
        long missing = boards.stream().filter(board -> weightOf(board) <= 0).count();
        return (double) missing / boards.size() > MISSING_WEIGHT_FALLBACK_RATIO;
    }

    /** 板块权重 = 总市值（&gt;0 才有效；等权回退时恒 1）。 */
    private static double weightOf(BoardQuote board) {
        return board.totalMv() == null || board.totalMv() <= 0 ? 0d : board.totalMv();
    }
}
