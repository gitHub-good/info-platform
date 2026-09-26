package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F2 行业热度传导单测（T170，方案 §4.2）：heatNorm=(31−rank)/30 名次归一（并列按行业名升序）、关联权重（次关联 0.5）、 30 天窗半衰期消退、多关联取
 * max、缺行热度记 0 分、空关联 F2=0。
 */
class ConductionFactorTest {

    private static final ValuationParams PARAMS = ValuationParams.defaults();

    /** 三行热度（银行 > 电子 > 食品饮料；其余 28 行缺行 → heatNorm 按 0 记，§5 降级预案）。 */
    private static List<HeatRow> heatRows() {
        return List.of(
                new HeatRow("银行", 812.4), new HeatRow("电子", 400.0), new HeatRow("食品饮料", 100.0));
    }

    @Test
    void emptyAssociations_scoreZero() {
        ConductionFactor.Result result = ConductionFactor.compute(List.of(), heatRows(), PARAMS);

        assertThat(result.score()).isZero();
        assertThat(result.assoc()).isEmpty();
    }

    @Test
    void emptyHeatTable_allHeatNormZero_degradedToZero() {
        // 热度快照缺行（异常降级）：关联在、热度不在 → raw2 = w×0×decay = 0
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 1.0, 0, IndustryAssociator.Source.EVENT)),
                        List.of(),
                        PARAMS);

        assertThat(result.score()).isZero();
    }

    @Test
    void topRankedIndustry_ageZero_fullHeatNorm() {
        // rank1 银行 heatNorm=(31−1)/30=1.0；age0 decay=1 → raw2=1.0 → F2=100
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 1.0, 0, IndustryAssociator.Source.EVENT)),
                        heatRows(),
                        PARAMS);

        assertThat(result.score()).isEqualTo(100.0);
        assertThat(result.assoc()).hasSize(1);
        assertThat(result.assoc().get(0).heatNorm()).isEqualTo(1.0);
        assertThat(result.assoc().get(0).heatH24()).isEqualTo(812.4);
    }

    @Test
    void rankTwoIndustry_heatNormTwentyNineOfThirty() {
        // 电子 rank2 → (31−2)/30 ≈ 0.96667
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "电子", 1.0, 0, IndustryAssociator.Source.NEWS_MAIN)),
                        heatRows(),
                        PARAMS);

        assertThat(result.assoc().get(0).heatNorm()).isCloseTo(29.0 / 30.0, within(1e-9));
        assertThat(result.score()).isCloseTo(100.0 * 29.0 / 30.0, within(1e-9));
    }

    @Test
    void subAssociation_halfWeight() {
        // 次关联 weight 0.5 × rank1 heatNorm 1.0 × age0 → F2 = 50
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 0.5, 0, IndustryAssociator.Source.NEWS_SUB)),
                        heatRows(),
                        PARAMS);

        assertThat(result.score()).isEqualTo(50.0);
        assertThat(result.assoc().get(0).source()).isEqualTo("NEWS_SUB");
    }

    @Test
    void associationFadesWithHalfLifeOfWindow() {
        // lastSeenAge=30=W2 → decay 0.5；rank1 → raw2 = 1.0×1.0×0.5 = 0.5 → F2=50
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 1.0, 30, IndustryAssociator.Source.EVENT)),
                        heatRows(),
                        PARAMS);

        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void multipleAssociations_maxContributionWins() {
        // 银行 rank1×age10（0.5^(10/30)=0.7937）vs 食品饮料 rank3(28/30=0.9333)×age0(1.0)
        // → 0.7937 < 0.9333 → 取食品饮料
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 1.0, 10, IndustryAssociator.Source.EVENT),
                                new IndustryAssociator.Association(
                                        "食品饮料", 1.0, 0, IndustryAssociator.Source.NEWS_MAIN)),
                        heatRows(),
                        PARAMS);

        assertThat(result.score()).isCloseTo(100.0 * (28.0 / 30.0), within(1e-9));
        assertThat(result.assoc().get(0).industry()).isEqualTo("食品饮料"); // 贡献降序首条
    }

    @Test
    void heatTie_rankBrokenByIndustryNameAsc_deterministic() {
        // 两行业并列同分：名次按行业名升序破并列（电子 < 银行 → 电子 rank1）——确定性幂等基石
        List<HeatRow> tie = List.of(new HeatRow("银行", 100.0), new HeatRow("电子", 100.0));

        ConductionFactor.Result bank =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "银行", 1.0, 0, IndustryAssociator.Source.EVENT)),
                        tie,
                        PARAMS);

        assertThat(bank.assoc().get(0).heatNorm()).isCloseTo(29.0 / 30.0, within(1e-9));
    }

    @Test
    void missingIndustryInHeatTable_zeroHeatNorm() {
        // 关联行业不在热度快照行内（缺行）→ heatNorm 0（如实 0 分不放大）
        ConductionFactor.Result result =
                ConductionFactor.compute(
                        List.of(
                                new IndustryAssociator.Association(
                                        "医药生物", 1.0, 0, IndustryAssociator.Source.EVENT)),
                        heatRows(),
                        PARAMS);

        assertThat(result.assoc().get(0).heatNorm()).isZero();
        assertThat(result.score()).isZero();
    }

    @Test
    void assocDetails_cappedAtTenByContribution() {
        List<IndustryAssociator.Association> associations = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            // age 递增 → 贡献递减，行业名保证不并列
            associations.add(
                    new IndustryAssociator.Association(
                            "行业" + String.format("%02d", i),
                            1.0,
                            i,
                            IndustryAssociator.Source.EVENT));
        }

        ConductionFactor.Result result =
                ConductionFactor.compute(
                        associations,
                        List.of(new HeatRow("行业01", 100.0), new HeatRow("行业02", 90.0)),
                        PARAMS);

        assertThat(result.assoc()).hasSize(10);
    }
}
