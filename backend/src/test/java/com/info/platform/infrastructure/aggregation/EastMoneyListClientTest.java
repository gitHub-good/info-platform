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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * EastMoneyListClient 单测（T50）：MockRestServiceServer 模拟东财 push2 clist 响应——<b>禁止真实外呼
 * push2</b>（实测密集请求触发 IP 级封禁，ADR-0027）。
 *
 * <p>覆盖（技术方案增补 §4.2 + §6 测试要点）：分页遍历拼接与 total 一致 / 请求参数契约（pn/pz/po/np/fltt/invt/fid=f12/fs/fields +
 * UA）/ text/plain JSON 容错 / total 完整性校验失败放弃 / data:null 放弃 / total 缺失放弃 / 单页 500 重试一次成功 / 重试耗尽放弃 /
 * 字段映射（f13 三市场前缀、f100 "-" → null、名称 trim、港股前导零）/ 空名行丢弃 / 重复 code 去重 / 未知市场码丢弃 / max-pages 防御上限 /
 * 页间礼貌 sleep / 单页覆盖 total 不多发请求。
 */
class EastMoneyListClientTest {

    private static final String LIST_URL = "https://push2.eastmoney.com/api/qt/clist/get";
    private static final String A_SHARE_FS =
            "m:0" + "+t:6,m:0" + "+t:80,m:1" + "+t:2,m:1" + "+t:23";

    /** 页间隔测试值：够断言「页间确有 sleep」又不拖慢测试。 */
    private static final long TEST_INTERVAL_MILLIS = 150;

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

    @Test
    void fetchAll_twoPages_assemblesMapsFieldsAndValidatesTotal() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.A_SHARE_STOCK,
                        2,
                        TEST_INTERVAL_MILLIS,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("pn=1")))
                                    .andExpect(requestTo(containsString("pz=2")))
                                    .andExpect(requestTo(containsString("po=1")))
                                    .andExpect(requestTo(containsString("np=1")))
                                    .andExpect(requestTo(containsString("fltt=2")))
                                    .andExpect(requestTo(containsString("invt=2")))
                                    .andExpect(requestTo(containsString("fid=f12")))
                                    .andExpect(requestTo(containsString("fs=" + A_SHARE_FS)))
                                    .andExpect(requestTo(containsString("fields=f12,f13,f14,f100")))
                                    .andExpect(method(HttpMethod.GET))
                                    // ISSUE-A：push2 WAF 对裸 UA 间歇断连，须带浏览器 UA
                                    .andExpect(header("User-Agent", containsString("Mozilla")))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            3,
                                                            row("600519", 1, "贵州茅台", "白酒"),
                                                            row("000001", 0, "平安银行", "银行")),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("pn=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(3, row("688111", 1, "金山办公", "办公软件")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots).hasSize(3);
        SubjectSnapshot first = snapshots.get(0);
        assertThat(first.subjectCode()).isEqualTo("SH600519");
        assertThat(first.name()).isEqualTo("贵州茅台");
        assertThat(first.industry()).isEqualTo("白酒");
        assertThat(first.secid()).isEqualTo("1.600519");
        assertThat(first.bucket()).isEqualTo(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(first.externalCodes())
                .containsEntry("eastmoney", "1.600519")
                .containsEntry("tushare", "600519.SH");
        // f13=0 → SZ 前缀
        assertThat(snapshots.get(1).subjectCode()).isEqualTo("SZ000001");
        assertThat(snapshots.get(1).secid()).isEqualTo("0.000001");
        assertThat(snapshots.get(1).externalCodes()).containsEntry("tushare", "000001.SZ");
        assertThat(snapshots.get(2).subjectCode()).isEqualTo("SH688111");
    }

    @Test
    void fetchAll_singlePageCoversTotal_noExtraRequest() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.HK_STOCK,
                        100,
                        TEST_INTERVAL_MILLIS,
                        200,
                        server ->
                                server.expect(requestTo(containsString("pn=1")))
                                        .andRespond(
                                                withSuccess(
                                                        page(1, row("00700", 116, " 腾讯控股 ", "-")),
                                                        MediaType.APPLICATION_JSON)));

        // 单页即覆盖 total：不发第二页请求（server.verify 校验无多余请求）
        assertThat(snapshots).hasSize(1);
        SubjectSnapshot tencent = snapshots.get(0);
        // 港股前导零保留 / f100 "-" → null / 名称 trim（§4.2 映射表）
        assertThat(tencent.subjectCode()).isEqualTo("HK00700");
        assertThat(tencent.secid()).isEqualTo("116.00700");
        assertThat(tencent.name()).isEqualTo("腾讯控股");
        assertThat(tencent.industry()).isNull();
        assertThat(tencent.externalCodes())
                .containsEntry("eastmoney", "116.00700")
                .containsEntry("tushare", "00700.HK");
    }

    @Test
    void fetchAll_politenessInterval_sleepsBetweenPages() {
        long start = System.nanoTime();
        fetchWithPages(
                MarketSyncSpec.A_SHARE_STOCK,
                1,
                TEST_INTERVAL_MILLIS,
                200,
                server -> {
                    server.expect(requestTo(containsString("pn=1")))
                            .andRespond(
                                    withSuccess(
                                            page(2, row("600519", 1, "贵州茅台", "白酒")),
                                            MediaType.APPLICATION_JSON));
                    server.expect(requestTo(containsString("pn=2")))
                            .andRespond(
                                    withSuccess(
                                            page(2, row("000001", 0, "平安银行", "银行")),
                                            MediaType.APPLICATION_JSON));
                });
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 页间隔 150ms × 1 次页间 sleep（防封禁礼貌限速，ADR-0027）
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(TEST_INTERVAL_MILLIS);
    }

    @Test
    void fetchAll_textPlainJsonBody_parsed() {
        // ISSUE-B：同族端点实测 JSON 体可能以 text/plain 声明返回，须容错读
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.A_SHARE_STOCK,
                        100,
                        0,
                        200,
                        server ->
                                server.expect(requestTo(containsString("pn=1")))
                                        .andRespond(
                                                withSuccess(
                                                        page(1, row("600519", 1, "贵州茅台", "白酒")),
                                                        MediaType.TEXT_PLAIN)));

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void fetchAll_totalMismatch_abandonsMarket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        2,
                                        0,
                                        200,
                                        server -> {
                                            server.expect(requestTo(containsString("pn=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(
                                                                            3,
                                                                            row(
                                                                                    "600519", 1,
                                                                                    "贵州茅台", "白酒"),
                                                                            row(
                                                                                    "000001", 0,
                                                                                    "平安银行", "银行")),
                                                                    MediaType.APPLICATION_JSON));
                                            // 第二页空 diff（提前翻空）→ 累计 2 != total 3
                                            server.expect(requestTo(containsString("pn=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(3),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性")
                .hasMessageContaining("collected=2")
                .hasMessageContaining("total=3");
    }

    @Test
    void fetchAll_dataNodeNull_abandonsMarket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.HK_STOCK,
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("pn=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "{\"rc\":0,\"rt\":6,"
                                                                                + "\"data\":null}",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data");
    }

    @Test
    void fetchAll_totalMissingOrNonPositive_abandonsMarket() {
        // 防御：total 缺失/非正（真实股票桶为千级）——防把「空名单」当全量误伤整桶标的
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("pn=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "{\"data\":{\"diff\":["
                                                                                + "{\"f12\":\"600519\",\"f13\":1,"
                                                                                + "\"f14\":\"贵州茅台\"}]}}",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total 非法");
    }

    @Test
    void fetchPage_serverError_retriedOnceThenSucceeds() {
        // §4.2 弹性：单页超时/失败重试 1 次（分页 GET 幂等）
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        MarketSyncSpec.A_SHARE_STOCK,
                        100,
                        0,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("pn=1")))
                                    .andRespond(withServerError());
                            server.expect(requestTo(containsString("pn=1")))
                                    .andRespond(
                                            withSuccess(
                                                    page(1, row("600519", 1, "贵州茅台", "白酒")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots).hasSize(1);
    }

    @Test
    void fetchAll_serverErrorExhaustsRetries_abandonsMarket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        100,
                                        0,
                                        200,
                                        server -> {
                                            server.expect(requestTo(containsString("pn=1")))
                                                    .andRespond(withServerError());
                                            server.expect(requestTo(containsString("pn=1")))
                                                    .andRespond(withServerError());
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 1 页拉取失败");
    }

    @Test
    void fetchAll_blankNameRow_dropped_thenTotalMismatchAbandons() {
        // §4.2 映射表：空名行丢弃记 WARN；行数缺口由 total 校验兜底（不写半截数据）
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("pn=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        page(
                                                                                1,
                                                                                row(
                                                                                        "600519", 1,
                                                                                        "  ",
                                                                                        "白酒")),
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    @Test
    void fetchAll_duplicateCodeDeduped_thenTotalMismatchAbandons() {
        // §7 风险表：翻页漂移重 1 行 → 去重留痕，total 校验放弃
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        100,
                                        0,
                                        200,
                                        server -> {
                                            server.expect(requestTo(containsString("pn=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(
                                                                            2,
                                                                            row(
                                                                                    "600519", 1,
                                                                                    "贵州茅台", "白酒"),
                                                                            row(
                                                                                    "600519", 1,
                                                                                    "贵州茅台", "白酒")),
                                                                    MediaType.APPLICATION_JSON));
                                            // 去重后累计 1 < total 2 → 翻第 2 页为空 diff → total 校验放弃
                                            server.expect(requestTo(containsString("pn=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(2),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性")
                .hasMessageContaining("collected=1");
    }

    @Test
    void fetchAll_unknownMarketFlag_dropped_thenTotalMismatchAbandons() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("pn=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        page(
                                                                                1,
                                                                                row(
                                                                                        "600519",
                                                                                        999, "贵州茅台",
                                                                                        "白酒")),
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    @Test
    void fetchAll_maxPagesExceeded_abandonsBucket() {
        // §4.2 防御上限：源 total 异常放大（如口径含北交所）时放弃该桶而非无限翻页
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        MarketSyncSpec.A_SHARE_STOCK,
                                        1,
                                        0,
                                        2,
                                        server -> {
                                            server.expect(requestTo(containsString("pn=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(
                                                                            3,
                                                                            row(
                                                                                    "600519", 1,
                                                                                    "贵州茅台", "白酒")),
                                                                    MediaType.APPLICATION_JSON));
                                            server.expect(requestTo(containsString("pn=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(
                                                                            3,
                                                                            row(
                                                                                    "000001", 0,
                                                                                    "平安银行", "银行")),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("防御上限")
                .hasMessageContaining("maxPages=2");
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端并执行 fetchAll；响应与请求校验由 responseSetter 设置。 */
    private List<SubjectSnapshot> fetchWithPages(
            MarketSyncSpec bucket,
            int pageSize,
            long pageIntervalMillis,
            int maxPages,
            java.util.function.Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyListClient client =
                new EastMoneyListClient(
                        builder, runner, LIST_URL, pageSize, pageIntervalMillis, maxPages);
        responseSetter.accept(server);
        List<SubjectSnapshot> snapshots = client.fetchAll(bucket);
        server.verify();
        return snapshots;
    }

    /** 构造 clist 响应体（结构按 2026-09-24 实测：data.total + data.diff[]）。 */
    private static String page(int total, String... rows) {
        StringBuilder sb =
                new StringBuilder("{\"data\":{\"total\":").append(total).append(",\"diff\":[");
        for (int i = 0; i < rows.length; i++) {
            sb.append(i == 0 ? "" : ",").append(rows[i]);
        }
        return sb.append("]}}").toString();
    }

    private static String row(String f12, int f13, String f14, String f100) {
        return "{\"f12\":\""
                + f12
                + "\",\"f13\":"
                + f13
                + ",\"f14\":\""
                + f14
                + "\",\"f100\":\""
                + f100
                + "\"}";
    }
}
