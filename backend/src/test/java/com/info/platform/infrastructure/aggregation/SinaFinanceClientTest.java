package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Year;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SinaFinanceClient 单测（ADR-0034 T55 财务备选源）：全 Mock 无真实外呼。
 *
 * <p>覆盖：两页拉取+GBK 解码+东财键映射（万元 ×10000→元、千分位剥离、REPORT_DATE 补 00:00:00、UA+Referer 头）/ 行名三行业变体
 * （通用/银行/保险）/ 加权 ROE 选择（摊薄干扰行不得命中）/ '--' 缺失不产出（金融股净利率/毛利率语义）/ 年份回退（当年空→前年）/ 双年空 empty / 指标页 HTTP
 * 失败整轮抛异常（宁 MISSING 不半套）/ 两页报告期错位对位 / 表格缺失防御 / 不可解析数值跳过。
 *
 * <p>夹具取 2026-09-24 架构 curl 实测口径（技术方案附录 A 与东财同报告期交叉核对零偏差），见 {@link SinaFinanceFixtures}。
 */
class SinaFinanceClientTest {

    private static final String PROFIT_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_ProfitStatement/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";
    private static final String GUIDE_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_FinancialGuideLine/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";

    /** 绑定 MockRestServiceServer 的客户端与服务端持有者（一次绑一对）。 */
    private record Bound(SinaFinanceClient client, MockRestServiceServer server) {}

    @Test
    void fetchFinance_twoPagesGbkDecoded_mapsToEastMoneyKeys() {
        int year = Year.now().getValue();
        Bound bound =
                bind(
                        server -> {
                            server.expect(requestTo(profitUrl("600519", year)))
                                    .andExpect(header("User-Agent", containsString("Mozilla")))
                                    .andExpect(header("Referer", containsString("sina.com.cn")))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_600519));
                            server.expect(requestTo(guideUrl("600519", year)))
                                    .andRespond(gbk(SinaFinanceFixtures.GUIDE_600519));
                        });

        Optional<Map<String, Object>> data = bound.client().fetchFinance("600519");

        bound.server().verify();
        assertThat(data).isPresent();
        Map<String, Object> row = data.get();
        assertThat(row.get("SECURITY_CODE")).isEqualTo("600519");
        // 报表日期取第 1 数据列（实测倒序列，最新报告期），补 00:00:00 对齐东财 REPORT_DATE 格式
        assertThat(row.get("REPORT_DATE")).isEqualTo("2026-06-30 00:00:00");
        // 万元 ×10000 → 元（对齐东财 TOTALOPERATEREVE/PARENTNETPROFIT 元单位；附录 A 零偏差口径）
        assertThat((BigDecimal) row.get("TOTALOPERATEREVE")).isEqualByComparingTo("92278072100");
        assertThat((BigDecimal) row.get("PARENTNETPROFIT")).isEqualByComparingTo("44516880400");
        // ROE 只取「加权净资产收益率」16.75（与东财 ROEJQ 一致）；摊薄行 17.72 在夹具中作干扰项不得命中
        assertThat((BigDecimal) row.get("ROEJQ")).isEqualByComparingTo("16.75");
        assertThat((BigDecimal) row.get("XSJLL")).isEqualByComparingTo("50.7516");
        // 毛利率 '--' → 键不产出（缺失值白名单语义）
        assertThat(row).doesNotContainKey("XSMLL");
    }

    @Test
    void fetchFinance_currentYearNoReport_fallsBackToPreviousYear() {
        int year = Year.now().getValue();
        Bound bound =
                bind(
                        server -> {
                            server.expect(requestTo(profitUrl("600519", year)))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_EMPTY_YEAR));
                            server.expect(requestTo(profitUrl("600519", year - 1)))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_600519_PRIOR_YEAR));
                            server.expect(requestTo(guideUrl("600519", year - 1)))
                                    .andRespond(gbk(SinaFinanceFixtures.GUIDE_600519_PRIOR_YEAR));
                        });

        Optional<Map<String, Object>> data = bound.client().fetchFinance("600519");

        // 恰好三请求：当年利润表无报告期行 → 前年利润表 + 前年指标页（当年指标页零请求）
        bound.server().verify();
        assertThat(data).isPresent();
        assertThat(data.get().get("REPORT_DATE")).isEqualTo("2025-12-31 00:00:00");
        assertThat((BigDecimal) data.get().get("TOTALOPERATEREVE"))
                .isEqualByComparingTo("170899353500");
    }

    @Test
    void fetchFinance_bothYearsEmpty_returnsEmpty() {
        int year = Year.now().getValue();
        Bound bound =
                bind(
                        server -> {
                            server.expect(requestTo(profitUrl("600519", year)))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_EMPTY_YEAR));
                            server.expect(requestTo(profitUrl("600519", year - 1)))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_EMPTY_YEAR));
                        });

        Optional<Map<String, Object>> data = bound.client().fetchFinance("600519");

        // 当年/前年均无报告期行 → empty（→ 链下一级/MISSING）；指标页零请求
        bound.server().verify();
        assertThat(data).isEmpty();
    }

    @Test
    void fetchFinance_guidePageHttpError_throwsWholeLevel() {
        int year = Year.now().getValue();
        Bound bound =
                bind(
                        server -> {
                            server.expect(requestTo(profitUrl("600519", year)))
                                    .andRespond(gbk(SinaFinanceFixtures.PROFIT_600519));
                            server.expect(requestTo(guideUrl("600519", year)))
                                    .andRespond(withServerError());
                        });

        // 两页组合非原子：指标页 HTTP 失败 → 该级异常整轮放弃（宁 MISSING 不半套，方案 §7 取舍）
        assertThatThrownBy(() -> bound.client().fetchFinance("600519"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void parseFinance_bankVariant_incomeRowAndParentProfitWithoutOwnerWord() {
        Optional<Map<String, Object>> data =
                SinaFinanceClient.parseFinance(
                        SinaFinanceFixtures.PROFIT_000001,
                        SinaFinanceFixtures.GUIDE_000001,
                        "000001");

        assertThat(data).isPresent();
        Map<String, Object> row = data.get();
        // 银行变体：营收取「一、营业收入」（无「一、营业总收入」行）；归母净利取「归属于母公司的净利润」（无「所有者」）
        assertThat((BigDecimal) row.get("TOTALOPERATEREVE")).isEqualByComparingTo("70617000000");
        assertThat((BigDecimal) row.get("PARENTNETPROFIT")).isEqualByComparingTo("25696000000");
        assertThat((BigDecimal) row.get("ROEJQ")).isEqualByComparingTo("5.22");
        // 金融股净利率/毛利率 '--' → 不产出（需求许可的降级展示）
        assertThat(row).doesNotContainKeys("XSJLL", "XSMLL");
    }

    @Test
    void parseFinance_insuranceVariant_shareholderParentProfit() {
        Optional<Map<String, Object>> data =
                SinaFinanceClient.parseFinance(
                        SinaFinanceFixtures.PROFIT_601318,
                        SinaFinanceFixtures.GUIDE_601318,
                        "601318");

        assertThat(data).isPresent();
        // 保险变体：归母净利取「归属于母公司股东的净利润」
        assertThat((BigDecimal) data.get().get("PARENTNETPROFIT"))
                .isEqualByComparingTo("92585000000");
        assertThat((BigDecimal) data.get().get("ROEJQ")).isEqualByComparingTo("9.00");
    }

    @Test
    void parseFinance_guidePeriodLagging_ratiosNotProduced_amountsStillProduced() {
        // 两页报告期一致性防御：指标页最新列落后利润表一期且无同报告期列 → 比率键不产出，营收/净利/报告期仍产出
        Optional<Map<String, Object>> data =
                SinaFinanceClient.parseFinance(
                        SinaFinanceFixtures.PROFIT_600519,
                        SinaFinanceFixtures.GUIDE_600519_LAGGING,
                        "600519");

        assertThat(data).isPresent();
        assertThat(data.get())
                .containsKeys(
                        "SECURITY_CODE", "REPORT_DATE", "TOTALOPERATEREVE", "PARENTNETPROFIT");
        assertThat(data.get()).doesNotContainKeys("ROEJQ", "XSJLL", "XSMLL");
    }

    @Test
    void parseFinance_guideTableMissing_ratiosNotProduced() {
        Optional<Map<String, Object>> data =
                SinaFinanceClient.parseFinance(
                        SinaFinanceFixtures.PROFIT_600519, "<html><body></body></html>", "600519");

        assertThat(data).isPresent();
        assertThat((BigDecimal) data.get().get("TOTALOPERATEREVE"))
                .isEqualByComparingTo("92278072100");
        assertThat(data.get()).doesNotContainKeys("ROEJQ", "XSJLL", "XSMLL");
    }

    @Test
    void parseFinance_profitTableMissing_returnsEmpty() {
        // 页面改版（表 id 缺席）→ empty（→ 链下一级/MISSING，不产出错误数值）
        assertThat(
                        SinaFinanceClient.parseFinance(
                                "<html><body>改版后无表格</body></html>", null, "600519"))
                .isEmpty();
    }

    @Test
    void parseFinance_unparseableAmount_skippedNotException() {
        String profit =
                """
                <table id="ProfitStatementNewTable0"><tbody>
                <tr><th>报表日期</th><td>2026-06-30</td></tr>
                <tr><th>一、营业总收入</th><td>N/A</td></tr>
                <tr><th>归属于母公司所有者的净利润</th><td>4,451,688.04</td></tr>
                </tbody></table>
                """;

        Optional<Map<String, Object>> data =
                SinaFinanceClient.parseFinance(profit, SinaFinanceFixtures.GUIDE_600519, "600519");

        assertThat(data).isPresent();
        // 不可解析数值 → 该键跳过（WARN 留痕），其余键正常产出
        assertThat(data.get()).doesNotContainKey("TOTALOPERATEREVE");
        assertThat((BigDecimal) data.get().get("PARENTNETPROFIT"))
                .isEqualByComparingTo("44516880400");
    }

    // ---- helpers ----

    private static Bound bind(Consumer<MockRestServiceServer> expectations) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectations.accept(server);
        return new Bound(new SinaFinanceClient(builder, PROFIT_TEMPLATE, GUIDE_TEMPLATE), server);
    }

    /** 响应体按 GBK 编码回给客户端（money.finance.sina.com.cn 实测 text/html; charset=gbk）。 */
    private static org.springframework.test.web.client.response.DefaultResponseCreator gbk(
            String html) {
        return withSuccess(html.getBytes(SinaFinanceFixtures.GBK), MediaType.TEXT_HTML);
    }

    private static String profitUrl(String code, int year) {
        return "https://sina.test/corp/go.php/vFD_ProfitStatement/stockid/"
                + code
                + "/ctrl/"
                + year
                + "/displaytype/4.phtml";
    }

    private static String guideUrl(String code, int year) {
        return "https://sina.test/corp/go.php/vFD_FinancialGuideLine/stockid/"
                + code
                + "/ctrl/"
                + year
                + "/displaytype/4.phtml";
    }
}
