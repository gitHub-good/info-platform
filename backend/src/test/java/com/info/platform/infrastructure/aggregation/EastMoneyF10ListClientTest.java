package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.infrastructure.common.ResilienceRunner;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * EastMoneyF10ListClient 单测（M29 T251）：MockRestServiceServer 模拟 datacenter F10 双报表响应——<b>禁止真实外呼
 * datacenter</b>（04 测试规范；实测留档归 Spike-E §1/§3 与 T251 首跑）。
 *
 * <p>覆盖（方案 §4 C1 + Spike-E E-1b 契约）：请求参数契约（reportName/columns/pageNumber/pageSize=500/source=F10 +
 * UA + Referer）/ 港股 5 位代码段过滤与行业 null→UNKNOWN / 美股 .N/.O 后缀 + 行业非空预筛与 105/106 secid 派生 / 分页遍历与 count
 * 完整性（原始行口径，过滤剔除不计缺口）/ 空页后 count 不符放弃 / result 缺失放弃 / 非法行容错（缺 SECUCODE/空名丢弃）/ f10 原键落
 * external_codes / 非港美股桶防御拒绝。
 */
class EastMoneyF10ListClientTest {

    private static final String F10_URL =
            "https://datacenter.eastmoney.com/securities/api/data/v1/get";

    /** 页间隔测试值：够走真实 sleep 分支又不拖慢测试。 */
    private static final long TEST_INTERVAL_MILLIS = 5;

    private ExecutorService exec;
    private ResilienceRunner runner;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        runner = new ResilienceRunner(exec);
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    // ---- 主路径：港股桶（分页 + 过滤 + 行业 UNKNOWN 归一）----

    @Test
    void fetchHk_twoPages_filtersNonStockCodes_andNormalizesNullIndustry() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.HK_STOCK,
                        server -> {
                            server.expect(
                                            requestTo(
                                                    containsString(
                                                            "reportName=RPT_HKF10_INFO_ORGPROFILE")))
                                    .andExpect(
                                            requestTo(
                                                    containsString(
                                                            "columns=SECUCODE,SECURITY_NAME_ABBR,BELONG_INDUSTRY")))
                                    .andExpect(requestTo(containsString("pageNumber=1")))
                                    .andExpect(requestTo(containsString("pageSize=500")))
                                    .andExpect(requestTo(containsString("sortColumns=SECUCODE")))
                                    .andExpect(requestTo(containsString("sortTypes=1")))
                                    .andExpect(requestTo(containsString("source=F10")))
                                    .andExpect(requestTo(containsString("client=PC")))
                                    .andExpect(method(HttpMethod.GET))
                                    .andExpect(header("User-Agent", containsString("Mozilla")))
                                    .andExpect(header("Referer", "https://data.eastmoney.com/"))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            4,
                                                            hkRow("00700.HK", "腾讯控股", "软件服务"),
                                                            hkRow("00005.HK", "汇丰控股", "银行"),
                                                            // .CMU 基金：非 5 位代码段 → 过滤剔除（原始行计数含它，count
                                                            // 校验通过）
                                                            hkRow("62040432.CMU", "施罗德基金", null)),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("pageNumber=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            4,
                                                            // 行业 null → UNKNOWN 枚举（港股全量入池口径）
                                                            hkRow("00405.HK", "越秀房产信托基金", null)),
                                                    MediaType.TEXT_PLAIN)); // text/plain JSON 容错
                        });

        assertThat(snapshots).hasSize(3);
        SubjectSnapshot tencent = findByCode(snapshots, "HK00700");
        assertThat(tencent.name()).isEqualTo("腾讯控股");
        assertThat(tencent.industry()).isEqualTo("软件服务");
        assertThat(tencent.secid()).isEqualTo("116.00700");
        assertThat(tencent.externalCodes())
                .containsEntry("eastmoney", "116.00700")
                .containsEntry("tushare", "00700.HK")
                .containsEntry("f10", "00700.HK");
        assertThat(findByCode(snapshots, "HK00405").industry()).isEqualTo("UNKNOWN");
        assertThat(findByCode(snapshots, "HK00005").industry()).isEqualTo("银行");
        // .CMU 行被过滤（非 5 位代码段）
        assertThat(snapshots).noneMatch(s -> s.subjectCode().contains("62040432"));
    }

    // ---- 主路径：美股桶（后缀 + 行业双预筛 + 105/106 secid 派生）----

    @Test
    void fetchUs_filtersSuffixAndIndustry_derivesSecidByExchange() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.US_STOCK,
                        server ->
                                server.expect(
                                                requestTo(
                                                        containsString(
                                                                "reportName=RPT_USF10_INFO_ORGPROFILE")))
                                        .andRespond(
                                                withSuccess(
                                                        page(
                                                                5,
                                                                usRow(
                                                                        "AAPL.O",
                                                                        "苹果",
                                                                        "电脑硬件、储存设备及电脑周边"),
                                                                usRow("A.N", "安捷伦", "生命科学工具和服务"),
                                                                // OTC .F → 后缀过滤剔除
                                                                usRow("AABVF.F", "Abacus", null),
                                                                // AMEX .A → 后缀过滤剔除（ADR-0064 裁决 3
                                                                // 边界）
                                                                usRow("ACU.A", "Acme", "办公服务与用品"),
                                                                // 主板后缀但行业 null → 预筛剔除
                                                                usRow("BRKB.N", "伯克希尔", null)),
                                                        MediaType.APPLICATION_JSON)));

        assertThat(snapshots).hasSize(2);
        SubjectSnapshot apple = findByCode(snapshots, "USAAPL");
        assertThat(apple.secid()).isEqualTo("105.AAPL"); // .O → 纳斯达克 105
        assertThat(apple.industry()).isEqualTo("电子设备与元件"); // 156 词归并 40 大类
        assertThat(apple.externalCodes())
                .containsEntry("eastmoney", "105.AAPL")
                .containsEntry("f10", "AAPL.O")
                .containsEntry("tushare", "AAPL.US");
        SubjectSnapshot agilent = findByCode(snapshots, "USA");
        assertThat(agilent.secid()).isEqualTo("106.A"); // .N → 纽交所 106
        assertThat(agilent.industry()).isEqualTo("医疗保健设备与服务");
    }

    // ---- 边界：单页覆盖 count 不多发请求 ----

    @Test
    void fetchHk_singlePageCoversCount_noExtraRequest() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.HK_STOCK,
                        server ->
                                server.expect(requestTo(containsString("pageNumber=1")))
                                        .andRespond(
                                                withSuccess(
                                                        page(1, hkRow("00700.HK", "腾讯控股", "软件服务")),
                                                        MediaType.APPLICATION_JSON)));

        assertThat(snapshots).hasSize(1);
    }

    // ---- 异常：空页终止后 count 不符（原始行口径）→ 该市场放弃 ----

    @Test
    void fetchHk_emptyPageThenCountMismatch_abandonsMarket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.HK_STOCK,
                                        server -> {
                                            server.expect(requestTo(containsString("pageNumber=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(
                                                                            3,
                                                                            hkRow(
                                                                                    "00700.HK",
                                                                                    "腾讯控股",
                                                                                    "软件服务")),
                                                                    MediaType.APPLICATION_JSON));
                                            server.expect(requestTo(containsString("pageNumber=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(3),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    // ---- 异常：result 缺失 / count 非法 → 放弃 ----

    @Test
    void fetchUs_missingResultNode_abandonsMarket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.US_STOCK,
                                        server ->
                                                server.expect(
                                                                requestTo(
                                                                        containsString(
                                                                                "pageNumber=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "{\"success\":true,\"result\":null,\"data\":null}",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("result 节点");
    }

    // ---- 异常：非法行容错（缺 SECUCODE / 空名丢弃，缺口由 count 校验兜底）----

    @Test
    void fetchHk_illegalRowsDropped_countMismatchAbandons() {
        // count=2 但一行缺 SECUCODE、一行空名 → 累计原始行 0 ≠ 2 → 放弃（不写半截数据）
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.HK_STOCK,
                                        server ->
                                                server.expect(
                                                                requestTo(
                                                                        containsString(
                                                                                "pageNumber=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        page(
                                                                                2,
                                                                                "{\"SECURITY_NAME_ABBR\":\"无代码\",\"BELONG_INDUSTRY\":\"银行\"}",
                                                                                hkRow(
                                                                                        "00005.HK",
                                                                                        "  ",
                                                                                        "银行")),
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    // ---- 异常：单页 5xx → 重试耗尽 → 该市场放弃（ResilienceSpec 包裹语义）----

    @Test
    void fetchHk_pageFailsAfterRetry_abandonsMarket() {
        // 超时/5xx 同语义：重试 1 次仍败 → IllegalStateException「重试耗尽」上抛（该市场零写入）
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.HK_STOCK,
                                        server -> {
                                            server.expect(requestTo(containsString("pageNumber=1")))
                                                    .andRespond(withServerError());
                                            server.expect(requestTo(containsString("pageNumber=1")))
                                                    .andRespond(withServerError());
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重试耗尽");
    }

    // ---- 防御：非港美股桶拒绝 ----

    @Test
    void fetchAll_nonHkUsBucket_rejected() {
        EastMoneyF10ListClient client =
                new EastMoneyF10ListClient(RestClient.builder(), runner, F10_URL, 500, 0, 10);
        assertThatThrownBy(() -> client.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅承载港美股桶");
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端并执行；响应与请求校验由 responseSetter 设置（verify 兜底无多余请求）。 */
    private List<SubjectSnapshot> fetchWithPages(
            MarketSyncSpec bucket, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyF10ListClient client =
                new EastMoneyF10ListClient(builder, runner, F10_URL, 500, TEST_INTERVAL_MILLIS, 10);
        responseSetter.accept(server);
        List<SubjectSnapshot> snapshots = client.fetchAll(bucket);
        server.verify();
        return snapshots;
    }

    /** 构造 F10 响应体（结构按 Spike-E E-1b 实测：result.count + result.data[]）。 */
    private static String page(int count, String... rows) {
        StringBuilder sb =
                new StringBuilder("{\"result\":{\"count\":").append(count).append(",\"data\":[");
        for (int i = 0; i < rows.length; i++) {
            sb.append(i == 0 ? "" : ",").append(rows[i]);
        }
        return sb.append("]},\"success\":true,\"code\":0}").toString();
    }

    private static String hkRow(String secucode, String name, String industry) {
        return row(secucode, name, industry);
    }

    private static String usRow(String secucode, String name, String industry) {
        return row(secucode, name, industry);
    }

    private static String row(String secucode, String name, String industry) {
        String industryJson = industry == null ? "null" : "\"" + industry + "\"";
        return "{\"SECUCODE\":\""
                + secucode
                + "\",\"SECURITY_NAME_ABBR\":\""
                + name
                + "\",\"BELONG_INDUSTRY\":"
                + industryJson
                + "}";
    }

    private static SubjectSnapshot findByCode(List<SubjectSnapshot> snapshots, String code) {
        return snapshots.stream()
                .filter(s -> s.subjectCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺标的: " + code));
    }
}
