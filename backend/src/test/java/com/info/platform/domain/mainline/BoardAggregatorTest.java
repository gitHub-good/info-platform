package com.info.platform.domain.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BoardAggregator 纯函数单测（M27 T242，方案 §4.2.2 + ADR-0063 裁决 2——§6 测试要点）：市值加权主口径 / 权重缺失 &gt;20% 回退等权 /
 * 加法列直聚 / 全部板块行缺席行业缺席 / 空输入。纯函数零依赖（无 Spring 上下文）。
 */
class BoardAggregatorTest {

    private static BoardQuote board(
            String name,
            String industry,
            Double pct,
            Double mv,
            Integer up,
            Integer down,
            Double flow) {
        return new BoardQuote(name, industry, pct, up, down, flow, mv);
    }

    @Test
    void aggregate_capWeighted_weightsByMarketValue() {
        // Arrange：电子 2 板块——权重 3000 亿 ×2% 与 1000 亿 ×1% → 加权 (3000×2 + 1000×1) / 4000 = 1.75%
        List<BoardQuote> boards =
                List.of(
                        board("半导体", "电子", 2.0, 3_000e8, 100, 50, 5e8),
                        board("消费电子", "电子", 1.0, 1_000e8, 40, 60, -2e8),
                        board("银行Ⅱ", "银行", 0.5, 10_000e8, 5, 3, 1e9));

        // Act
        List<IndustryQuote> rows = BoardAggregator.aggregate(boards);

        // Assert：2 行业各一行（3 板块聚合为 电子/银行）；电子市值加权 + 加法列直聚；aggMethod 留痕
        assertThat(rows).hasSize(2);
        IndustryQuote electronics =
                rows.stream().filter(r -> r.industry().equals("电子")).findFirst().orElseThrow();
        assertThat(electronics.pctDay()).isCloseTo(1.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(electronics.upCount()).isEqualTo(140);
        assertThat(electronics.downCount()).isEqualTo(110);
        assertThat(electronics.mainNetFlow())
                .isCloseTo(3e8, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(electronics.totalMv())
                .isCloseTo(4_000e8, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(electronics.aggMethod()).isEqualTo("CAP_WEIGHTED");
        assertThat(electronics.leaderStock()).isNull();
        assertThat(
                        rows.stream()
                                .filter(r -> r.industry().equals("银行"))
                                .findFirst()
                                .orElseThrow()
                                .aggMethod())
                .isEqualTo("CAP_WEIGHTED");
    }

    @Test
    void aggregate_missingWeightOver20Percent_fallsBackToEqualWeight() {
        // Arrange：某行业 5 板块中 2 板块（40% > 20%）f20 缺失 → 等权回退：mean(1,2,3) = 2.0
        List<BoardQuote> boards =
                List.of(
                        board("白酒Ⅱ", "食品饮料", 1.0, 100e8, 10, 5, 1e8),
                        board("非白酒", "食品饮料", 2.0, 100e8, 20, 5, 1e8),
                        board("休闲食品", "食品饮料", 3.0, 100e8, 30, 5, 1e8),
                        board("调味发酵品Ⅱ", "食品饮料", 0.5, null, 5, 5, null),
                        board("饮料乳品", "食品饮料", 0.5, -1d, 5, 5, 0d));

        // Act
        List<IndustryQuote> rows = BoardAggregator.aggregate(boards);

        // Assert：等权口径（有 pct 的 5 行全进均值 = (1+2+3+0.5+0.5)/5 = 1.4）+ EQUAL 留痕
        IndustryQuote food =
                rows.stream().filter(r -> r.industry().equals("食品饮料")).findFirst().orElseThrow();
        assertThat(food.aggMethod()).isEqualTo("EQUAL");
        assertThat(food.pctDay()).isCloseTo(1.4, org.assertj.core.data.Offset.offset(1e-9));
        // 加法列仍直聚（null 记 0）
        assertThat(food.mainNetFlow()).isCloseTo(3e8, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(food.totalMv()).isCloseTo(300e8, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void aggregate_missingWeightAtThreshold_staysCapWeighted() {
        // Arrange：5 板块恰 1 板块缺失（20%，不 > 20%）→ 维持市值加权：(3×1 + 缺失行不进均值...) 缺失 pct 行不进平均
        List<BoardQuote> boards =
                List.of(
                        board("白酒Ⅱ", "食品饮料", 2.0, 300e8, 10, 5, 1e8),
                        board("非白酒", "食品饮料", 1.0, 100e8, 20, 5, 1e8),
                        board("休闲食品", "食品饮料", 0.5, null, 5, 5, null),
                        board("调味发酵品Ⅱ", "食品饮料", 3.0, 100e8, 8, 2, 0d),
                        board("饮料乳品", "食品饮料", 1.5, 100e8, 6, 4, 0d));

        // Act
        IndustryQuote food =
                BoardAggregator.aggregate(boards).stream()
                        .filter(r -> r.industry().equals("食品饮料"))
                        .findFirst()
                        .orElseThrow();

        // Assert：缺失行（mv null 且 pct 0.5）不进涨跌幅平均 → (300×2 + 100×1 + 100×3 + 100×1.5) / 600 ≈ 1.9167
        assertThat(food.aggMethod()).isEqualTo("CAP_WEIGHTED");
        assertThat(food.pctDay())
                .isCloseTo(1150d / 600d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aggregate_industryWithoutBoards_absentFromOutput() {
        // Arrange：仅电子板块行（医药生物无板块行——快照缺口由通道 B 补或如实缺席）
        List<BoardQuote> boards = List.of(board("半导体", "电子", 2.0, 1_000e8, 10, 5, 1e8));

        // Act + Assert
        List<IndustryQuote> rows = BoardAggregator.aggregate(boards);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).industry()).isEqualTo("电子");
    }

    @Test
    void aggregate_emptyInput_returnsEmpty() {
        assertThat(BoardAggregator.aggregate(List.of())).isEmpty();
    }

    @Test
    void aggregate_nullPctRows_excludedFromAverageOnly() {
        // Arrange：2 板块其一停牌（f3 null）→ 均值只取有值行；家数/市值仍直聚
        List<BoardQuote> boards =
                List.of(
                        board("半导体", "电子", 2.0, 1_000e8, 10, 5, 1e8),
                        board("消费电子", "电子", null, 1_000e8, 0, 0, 0d));

        // Act
        IndustryQuote electronics =
                BoardAggregator.aggregate(boards).stream()
                        .filter(r -> r.industry().equals("电子"))
                        .findFirst()
                        .orElseThrow();

        // Assert
        assertThat(electronics.pctDay()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(electronics.totalMv())
                .isCloseTo(2_000e8, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void aggregate_deterministic_sameInputTwiceEquals() {
        List<BoardQuote> boards =
                List.of(
                        board("半导体", "电子", 2.0, 3_000e8, 100, 50, 5e8),
                        board("银行Ⅱ", "银行", 0.5, 10_000e8, 5, 3, 1e9),
                        board("证券Ⅱ", "非银金融", -1.0, 2_000e8, 20, 80, -3e8));

        assertThat(BoardAggregator.aggregate(boards)).isEqualTo(BoardAggregator.aggregate(boards));
    }
}
