package com.info.platform.domain.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.mainline.LeaderCalculator.Candidate;
import com.info.platform.domain.mainline.LeaderCalculator.LeaderRow;
import com.info.platform.domain.mainline.LeaderCalculator.Params;
import com.info.platform.domain.mainline.LeaderCalculator.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LeaderCalculator 纯函数单测（M27 T244，方案 §3.5 + ADR-0063 裁决 5——§6 测试要点）：综合分矩阵（主维主导）/ 排序与龙次 / 价值缺数 50 中性
 * / 停牌 NO_MARKET_DATA 保留 / 并列确定性 / 同输入重算零漂移。ST 排除在服务装载层（留痕面归 funnel—— IndustryMainlineServiceTest
 * 覆盖）。
 */
class LeaderCalculatorTest {

    private static final Params DEFAULT_PARAMS = new Params(0.50, 0.35, 0.15, 7, 3, 0.5, 0.5);

    private static Candidate candidate(
            long id,
            String code,
            String name,
            int mentions,
            double events,
            Double value,
            Double pctDay,
            Double pctD5) {
        return new Candidate(
                id, code, name, mentions, events, (int) events, 0, value, "[]", pctDay, pctD5);
    }

    @Test
    void calculate_attentionDominant_composesAndRanks() {
        List<Candidate> candidates =
                List.of(
                        candidate(1, "600519", "贵州茅台", 12, 7d, 76.5, 0.56, 2.10),
                        candidate(2, "000858", "五粮液", 6, 1d, 70.0, 1.10, 4.00),
                        candidate(3, "601579", "会稽山", 0, 0d, 60.0, 9.99, 12.0),
                        candidate(4, "300162", "雷曼光电", 0, 0d, 55.0, 12.89, 20.0));

        Result result = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);

        // 资讯关注度主维（0.50）：茅台 A=19 全顶 → 龙一（价格维垫底不颠覆主维主导，wq=0.15 条件维）
        assertThat(result.leaders()).hasSize(3);
        LeaderRow first = result.leaders().get(0);
        assertThat(first.subjectCode()).isEqualTo("600519");
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.attention().rank()).isEqualTo(1);
        assertThat(first.score()).isGreaterThan(0);
        // 纯价格脉冲（雷曼 12.89% 涨停 + 20% 五日）不进前三：A=0 主维垫底
        assertThat(result.leaders()).noneMatch(row -> row.subjectCode().equals("300162"));
    }

    @Test
    void calculate_valueMissing_dimensionNeutralWithFlag() {
        List<Candidate> candidates =
                List.of(
                        candidate(1, "600519", "贵州茅台", 12, 7d, null, 0.56, 2.10),
                        candidate(2, "000858", "五粮液", 6, 1d, null, 1.10, 4.00));

        Result result = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);

        assertThat(result.dimensionMissing()).containsEntry("value", true);
        assertThat(result.leaders()).hasSize(2);
        // 价值维两行中性 50 并列——排序由主维决定
        assertThat(result.leaders().get(0).subjectCode()).isEqualTo("600519");
        assertThat(result.leaders().get(0).value().flag()).isEqualTo("DIMENSION_MISSING");
    }

    @Test
    void calculate_suspendedKept_noMarketDataFlag() {
        List<Candidate> candidates =
                List.of(
                        candidate(1, "600519", "贵州茅台", 12, 7d, 76.5, 0.56, 2.10),
                        candidate(2, "000001", "平安银行", 8, 2d, 70.0, null, null));

        Result result = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);

        // 停牌保留：价格维缺数 50 中性 + flag NO_MARKET_DATA（沿 M20 例外口径）
        LeaderRow suspended =
                result.leaders().stream()
                        .filter(row -> row.subjectCode().equals("000001"))
                        .findFirst()
                        .orElseThrow();
        assertThat(suspended.price().flag()).isEqualTo("NO_MARKET_DATA");
        assertThat(result.dimensionMissing()).containsEntry("price", false);
    }

    @Test
    void calculate_priceDimensionAllMissing_neutralFlagged() {
        List<Candidate> candidates =
                List.of(
                        candidate(1, "600519", "贵州茅台", 12, 7d, 76.5, null, null),
                        candidate(2, "000858", "五粮液", 6, 1d, 88.0, null, null));

        Result result = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);

        assertThat(result.dimensionMissing()).containsEntry("price", true);
        // 主维 12+7 > 6+1 → 茅台龙一（价格中性不改变主维序）
        assertThat(result.leaders().get(0).subjectCode()).isEqualTo("600519");
    }

    @Test
    void calculate_tiesDeterministic_sameInputZeroDrift() {
        List<Candidate> candidates =
                List.of(
                        candidate(1, "600000", "甲", 5, 2d, 70d, 1.0, 2.0),
                        candidate(2, "000001", "乙", 5, 2d, 70d, 1.0, 2.0),
                        candidate(3, "000002", "丙", 8, 3d, 80d, 2.0, 3.0));

        Result first = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);
        Result second = LeaderCalculator.calculate(DEFAULT_PARAMS, candidates);

        assertThat(first).isEqualTo(second);
        // 并列按代码升序确定性（000001 < 600000）
        assertThat(first.leaders().get(1).subjectCode()).isEqualTo("000001");
        assertThat(first.leaders().get(2).subjectCode()).isEqualTo("600000");
        assertThat(first.leaders().get(1).score()).isEqualTo(first.leaders().get(2).score());
    }

    @Test
    void calculate_topNTruncates_emptyCandidatesReturnEmpty() {
        List<Candidate> five =
                List.of(
                        candidate(1, "600001", "a", 1, 0d, 50d, 0d, 0d),
                        candidate(2, "600002", "b", 2, 0d, 50d, 0d, 0d),
                        candidate(3, "600003", "c", 3, 0d, 50d, 0d, 0d),
                        candidate(4, "600004", "d", 4, 0d, 50d, 0d, 0d),
                        candidate(5, "600005", "e", 5, 0d, 50d, 0d, 0d));

        assertThat(LeaderCalculator.calculate(DEFAULT_PARAMS, five).leaders()).hasSize(3);
        assertThat(LeaderCalculator.calculate(DEFAULT_PARAMS, List.of()).leaders()).isEmpty();
    }
}
