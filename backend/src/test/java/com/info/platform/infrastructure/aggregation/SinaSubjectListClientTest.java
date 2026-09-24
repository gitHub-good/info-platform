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
 * SinaSubjectListClient 单测（M7 备选源，ADR-0030）：MockRestServiceServer 模拟新浪 {@code
 * Market_Center.getHQNodeData} 响应——<b>禁止真实外呼新浪</b>（对齐 EastMoneyListClientTest 的禁真实外呼约定）。
 *
 * <p>覆盖：分页终止条件（空页 {@code []} / 不足 num 短页 / 首页空防假空）/ bj 北交所前缀跳过 / 字段映射与 secid 派生（沪 1.代码、深
 * 0.代码，对齐东财快照形态）/ 行业恒 null（源无行业字段，PM 裁「以源为准」）/ 请求契约（page/num/sort=symbol/asc=1/node=hs_a + UA +
 * Referer）/ 单页 500 重试一次成功 / 重试耗尽放弃 / max-pages 防御上限 / 页间礼貌 sleep / 空名行丢弃 / 重复 code 去重 / 非 A 股桶不支持。
 */
class SinaSubjectListClientTest {

    private static final String LIST_URL =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeData";

    /** 新浪软限频要求的来源页（防 403，与 SinaNewsClient 同款）。 */
    private static final String REFERER = "https://finance.sina.com.cn";

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
    void fetchAll_shortLastPage_mapsFieldsAndDerivesSecid() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        2,
                        TEST_INTERVAL_MILLIS,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("page=1")))
                                    .andExpect(requestTo(containsString("num=2")))
                                    .andExpect(requestTo(containsString("sort=symbol")))
                                    .andExpect(requestTo(containsString("asc=1")))
                                    .andExpect(requestTo(containsString("node=hs_a")))
                                    .andExpect(method(HttpMethod.GET))
                                    // 新浪软限频：必须带浏览器 UA + finance.sina.com.cn Referer（ADR-0030）
                                    .andExpect(header("User-Agent", containsString("Mozilla")))
                                    .andExpect(header("Referer", REFERER))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            row("sh600519", "贵州茅台"),
                                                            row("sz000001", "平安银行")),
                                                    MediaType.APPLICATION_JSON));
                            // 第二页不足 num（1 < 2）→ 最后一页，终止且不发第三页请求
                            server.expect(requestTo(containsString("page=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(row("sh688111", "金山办公")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots).hasSize(3);
        SubjectSnapshot first = snapshots.get(0);
        // sh → SH 前缀 / secid 沪 1.代码（对齐东财快照，行情链路零改造）
        assertThat(first.subjectCode()).isEqualTo("SH600519");
        assertThat(first.name()).isEqualTo("贵州茅台");
        assertThat(first.secid()).isEqualTo("1.600519");
        assertThat(first.bucket()).isEqualTo(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(first.externalCodes())
                .containsEntry("eastmoney", "1.600519")
                .containsEntry("tushare", "600519.SH");
        // sz → SZ 前缀 / secid 深 0.代码
        assertThat(snapshots.get(1).subjectCode()).isEqualTo("SZ000001");
        assertThat(snapshots.get(1).secid()).isEqualTo("0.000001");
        assertThat(snapshots.get(1).externalCodes()).containsEntry("tushare", "000001.SZ");
        assertThat(snapshots.get(2).subjectCode()).isEqualTo("SH688111");
        // 源无行业字段 → 恒 null（PM 裁「以源为准」，ADR-0030）
        assertThat(snapshots).allSatisfy(s -> assertThat(s.industry()).isNull());
    }

    @Test
    void fetchAll_bjPrefixRows_skipped() {
        // 北交所不在需求口径（fs 不含 bj，ADR-0027）；新浪 node=hs_a 含 bj 前缀行，客户端过滤
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        3,
                        0,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("page=1")))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            row("bj920000", "安徽凤凰"),
                                                            row("sh600519", "贵州茅台"),
                                                            row("sz000001", "平安银行")),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("page=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(row("bj920099", "北证样本")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots)
                .extracting(SubjectSnapshot::subjectCode)
                .containsExactly("SH600519", "SZ000001");
    }

    @Test
    void fetchAll_firstPageEmptyArray_abandonsBucket() {
        // 防假空：首页即空（源故障/封禁返 []）不能当「全量为空」（否则整桶误判缺失）——放弃该桶
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("page=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "[]",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("首页为空");
    }

    @Test
    void fetchAll_firstPageNullBody_abandonsBucket() {
        // 新浪翻页越界返回字面量 null（实测 2026-09-22）：首页 null 同防假空
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        100,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("page=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "null",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("首页为空");
    }

    @Test
    void fetchAll_trailingEmptyPage_terminates() {
        // 尾页后的空页 []：整页取尽终止（与短页终止并列的两条终止条件之一）
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        1,
                        0,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("page=1")))
                                    .andRespond(
                                            withSuccess(
                                                    page(row("sh600519", "贵州茅台")),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("page=2")))
                                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void fetchAll_blankNameRowDropped_resultEmpty_abandons() {
        // 空名行丢弃；映射后全量为空 → 防假空放弃（与首页空同一条防御兜底）
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        2,
                                        0,
                                        200,
                                        server ->
                                                server.expect(requestTo(containsString("page=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        page(row("sh600519", "  ")),
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("全量结果为空");
    }

    @Test
    void fetchAll_duplicateCodeDeduped() {
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        2,
                        0,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("page=1")))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            row("sh600519", "贵州茅台"),
                                                            row("sh600519", "贵州茅台")),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("page=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(row("sz000001", "平安银行")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots)
                .extracting(SubjectSnapshot::subjectCode)
                .containsExactly("SH600519", "SZ000001");
    }

    @Test
    void fetchAll_unsupportedBucket_rejectedWithoutRequest() {
        // 端口契约：新浪仅服务 A 股桶（无港股节点，ADR-0030）——港股/指数桶由路由层恒走东财
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SinaSubjectListClient client = newClient(builder, 100, 0, 200);

        assertThatThrownBy(() -> client.fetchAll(MarketSyncSpec.HK_STOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅支持");
        server.verify();
    }

    @Test
    void fetchPage_serverError_retriedOnceThenSucceeds() {
        // 单页弹性（对齐东财 clist 同款：超时/重试 1 + 退避，分页 GET 幂等；num=2 下 1 行即短页终止）
        List<SubjectSnapshot> snapshots =
                fetchWithPages(
                        2,
                        0,
                        200,
                        server -> {
                            server.expect(requestTo(containsString("page=1")))
                                    .andRespond(withServerError());
                            server.expect(requestTo(containsString("page=1")))
                                    .andRespond(
                                            withSuccess(
                                                    page(row("sh600519", "贵州茅台")),
                                                    MediaType.APPLICATION_JSON));
                        });

        assertThat(snapshots).hasSize(1);
    }

    @Test
    void fetchAll_serverErrorExhaustsRetries_abandonsBucket() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        100,
                                        0,
                                        200,
                                        server -> {
                                            server.expect(requestTo(containsString("page=1")))
                                                    .andRespond(withServerError());
                                            server.expect(requestTo(containsString("page=1")))
                                                    .andRespond(withServerError());
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 1 页拉取失败");
    }

    @Test
    void fetchAll_maxPagesExceeded_abandonsBucket() {
        // 防御上限：源异常放大（口径漂移）时放弃该桶而非无限翻页
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        1,
                                        0,
                                        1,
                                        server -> {
                                            server.expect(requestTo(containsString("page=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(row("sh600519", "贵州茅台")),
                                                                    MediaType.APPLICATION_JSON));
                                            server.expect(requestTo(containsString("page=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(row("sz000001", "平安银行")),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("防御上限")
                .hasMessageContaining("maxPages=1");
    }

    @Test
    void fetchAll_politenessInterval_sleepsBetweenPages() {
        long start = System.nanoTime();
        fetchWithPages(
                2,
                TEST_INTERVAL_MILLIS,
                200,
                server -> {
                    server.expect(requestTo(containsString("page=1")))
                            .andRespond(
                                    withSuccess(
                                            page(row("sh600519", "贵州茅台"), row("sh688111", "金山办公")),
                                            MediaType.APPLICATION_JSON));
                    // 第二页 1 行 < num=2（短页终止）
                    server.expect(requestTo(containsString("page=2")))
                            .andRespond(
                                    withSuccess(
                                            page(row("sz000001", "平安银行")),
                                            MediaType.APPLICATION_JSON));
                });
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 页间隔 150ms × 1 次页间 sleep（复用 subject.sync.page-interval-millis，ADR-0030）
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(TEST_INTERVAL_MILLIS);
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端并执行 A 股桶 fetchAll；响应与请求校验由 responseSetter 设置。 */
    private List<SubjectSnapshot> fetchWithPages(
            int pageSize,
            long pageIntervalMillis,
            int maxPages,
            java.util.function.Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SinaSubjectListClient client = newClient(builder, pageSize, pageIntervalMillis, maxPages);
        responseSetter.accept(server);
        List<SubjectSnapshot> snapshots = client.fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        server.verify();
        return snapshots;
    }

    private SinaSubjectListClient newClient(
            RestClient.Builder builder, int pageSize, long pageIntervalMillis, int maxPages) {
        return new SinaSubjectListClient(
                builder, runner, LIST_URL, pageSize, pageIntervalMillis, maxPages, REFERER);
    }

    /** 构造新浪响应体（裸 JSON 数组，2026-09-22 实测；非 ASCII 由源按 JSON unicode 转义输出，编码无歧义）。 */
    private static String page(String... rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.length; i++) {
            sb.append(i == 0 ? "" : ",").append(rows[i]);
        }
        return sb.append("]").toString();
    }

    private static String row(String symbol, String name) {
        return "{\"symbol\":\""
                + symbol
                + "\",\"code\":\""
                + symbol.substring(2)
                + "\",\"name\":\""
                + name
                + "\"}";
    }
}
