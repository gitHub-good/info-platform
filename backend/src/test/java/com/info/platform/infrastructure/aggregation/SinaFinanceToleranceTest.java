package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 新浪备选 × 东财主源同报告期容差对拍回归（ADR-0034 T58，固化技术方案附录 A 工差核对表 600519 样本进回归，防口径漂移）。
 *
 * <p>对拍口径：新浪 vFD 两页夹具（{@link SinaFinanceFixtures}，2026-09-24 实测）经 {@link
 * SinaFinanceClient#parseFinance} 解析（万元 ×10000 → 元 / 加权 ROE / 剔摊薄干扰行）vs 东财 F10 同日 curl 实测值——
 * 上线门槛（REQ-20260924-05）：营收/归母净利相对偏差 ≤1%，ROE/净利率差 ≤0.5pp；实测口径 0.0000% / 0.00pp（万元舍入量级）。
 *
 * <p>本用例锁的是「解析口径」不漂移：若 {@link SinaFinanceClient} 行名匹配/单位换算/口径选择回归（如误取摊薄 ROE 17.72），本用例即红。
 */
class SinaFinanceToleranceTest {

    /** 上线门槛：营收/归母净利相对偏差上限（REQ-20260924-05）。 */
    private static final double RELATIVE_TOLERANCE = 0.01;

    /** 上线门槛：ROE/净利率差上限（百分点）。 */
    private static final double RATIO_TOLERANCE_PP = 0.5;

    /**
     * 东财 F10 同报告期实测值（2026-09-24 curl，600519 2026-06-30 一条；与 FinanceSourceAdapterFallbackTest 夹具同源）。
     */
    private static final BigDecimal EAST_REVENUE = new BigDecimal("92278072083.21");

    private static final BigDecimal EAST_PARENT_NET_PROFIT = new BigDecimal("44516880421.86");

    private static final BigDecimal EAST_ROE = new BigDecimal("16.75");

    private static final BigDecimal EAST_NET_PROFIT_MARGIN = new BigDecimal("50.75");

    private static final String EAST_REPORT_DATE = "2026-06-30 00:00:00";

    @Test
    void appendixA_moutai2026H1_crossSource_deviationWithinThreshold() {
        Optional<Map<String, Object>> parsed =
                SinaFinanceClient.parseFinance(
                        SinaFinanceFixtures.PROFIT_600519,
                        SinaFinanceFixtures.GUIDE_600519,
                        "600519");

        assertThat(parsed).as("两页夹具解析必须产出最小完备集").isPresent();
        Map<String, Object> sina = parsed.get();

        // 报告期口径一致（TTM-only 不合格——本对拍锁报告期累计口径）
        assertThat(sina.get("REPORT_DATE")).isEqualTo(EAST_REPORT_DATE);

        // 营收/归母净利：万元 ×10000 → 元后与东财同报告期相对偏差 ≤1%（实测 ~1.8e-10，万元两位小数舍入量级）
        assertThat(relativeDeviation((BigDecimal) sina.get("TOTALOPERATEREVE"), EAST_REVENUE))
                .as("营收相对偏差")
                .isLessThanOrEqualTo(RELATIVE_TOLERANCE);
        assertThat(
                        relativeDeviation(
                                (BigDecimal) sina.get("PARENTNETPROFIT"), EAST_PARENT_NET_PROFIT))
                .as("归母净利相对偏差")
                .isLessThanOrEqualTo(RELATIVE_TOLERANCE);

        // ROE/净利率：差 ≤0.5pp（ROE 为加权口径 16.75——误取摊薄行 17.72 会以 0.97pp 超门槛被本用例拦截）
        assertThat(ratioDiffPP((BigDecimal) sina.get("ROEJQ"), EAST_ROE))
                .as("加权 ROE 差(pp)")
                .isLessThanOrEqualTo(RATIO_TOLERANCE_PP);
        assertThat(ratioDiffPP((BigDecimal) sina.get("XSJLL"), EAST_NET_PROFIT_MARGIN))
                .as("净利率差(pp)")
                .isLessThanOrEqualTo(RATIO_TOLERANCE_PP);

        // 毛利率 '--' 缺失不产出（金融股大面积缺失同语义，需求许可降级展示）
        assertThat(sina).doesNotContainKey("XSMLL");
    }

    /** 相对偏差：|备选 - 主源| / |主源|。 */
    private static double relativeDeviation(BigDecimal sina, BigDecimal east) {
        return sina.subtract(east).abs().doubleValue() / east.abs().doubleValue();
    }

    /** 比率差（百分点）：|备选 - 主源|。 */
    private static double ratioDiffPP(BigDecimal sina, BigDecimal east) {
        return sina.subtract(east).abs().doubleValue();
    }
}
