package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AnnounceSourceAdapter 分页取数单测（M12 T90，REQ-20260925-09）：{@code fetchPage} 绕缓存直调源（page_index 透传 +
 * total_hits 透出）+ 巨潮降级分页语义 + 越界页 200 空列表不触发兜底 + 首屏 {@code doFetch} 附带分页元数据（sectionPagination
 * 提取口径）。
 *
 * <p>行为测试用 {@link MockRestServiceServer}（结构 {@code data.list[]}，2026-09-22 深翻实测：page_index=1/2/3/5/6 均
 * 200×10 条、total_hits=1074 各页恒定、notice_date 跨页倒序衔接）。巨潮降级形态对齐 {@code
 * AnnounceSourceAdapterFallbackTest} 夹具（orgId 表 + POST 查询）；东财/巨潮两 client 各绑独立 mock server，期望按 URL
 * 区分各自注册、各自 verify。
 */
class AnnounceSourceAdapterPageTest {

    private static final String EAST_URL = "https://np-anotice-stock.test/api/security/ann";
    private static final String EAST_REFERER = "https://data.eastmoney.com/";
    private static final String EAST_DETAIL_TEMPLATE =
            "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf";
    private static final String CNINFO_QUERY_URL = "https://cninfo.test/new/hisAnnouncement/query";
    private static final String CNINFO_STOCK_LIST_URL =
            "https://cninfo.test/new/data/szse_stock.json";
    private static final String CNINFO_DETAIL_PREFIX = "https://static.cninfo.test/";
    private static final Duration ORG_ID_TTL = Duration.ofHours(24);

    private static final String CNINFO_STOCK_LIST_JSON =
            """
            [{"orgId":"gssh0600519","category":"A股","code":"600519","pinyin":"GZMT","zwjc":"贵州茅台"}]
            """;

    private static final String CNINFO_QUERY_JSON =
            """
            {"announcements":[
              {"announcementId":"1225475868","announcementTitle":"贵州茅台:2026年半年度报告",
               "announcementTime":1786723200000,"secCode":"600519","secName":"贵州茅台",
               "adjunctUrl":"finalpage/2026-08-15/1225475868.PDF"}],
             "totalRecordNum":486,"hasMore":true,"pageNum":1}
            """;

    private ExecutorService exec;
    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;
    private NoopCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(new ObjectMapper());
        runner = new ResilienceRunner(exec);
        breaker = new NoopCircuitBreaker();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    // ---- fetchPage · 东财路径 ----

    @Test
    void fetchPage_eastMoney_passesPageIndexAndSize_exposesTotalAndMoreUrl() {
        // Arrange：page=2、size=10 透传（page_index=2 & page_size=10 进 URL，2026-09-22 实测口径）
        SourceResult result =
                fetchPage(2, 10, (east, cninfo) -> east.expect(requestTo(containsString("stock_list=600519")))
                        .andExpect(requestTo(containsString("page_index=2")))
                        .andExpect(requestTo(containsString("page_size=10")))
                        .andExpect(requestTo(containsString("sr=-1")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("User-Agent", containsString("Mozilla")))
                        .andRespond(withSuccess(eastPageJson(2, 10, 1074, 2), MediaType.APPLICATION_JSON)));

        // Assert：OK + total_hits 透出 + paginationSupported=true + moreUrl 东财源站出口
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告");
        assertThat(result.getData().get("total")).isEqualTo(1074L);
        assertThat(result.getData().get("paginationSupported")).isEqualTo(true);
        assertThat(result.getData().get("moreUrl"))
                .isEqualTo("https://data.eastmoney.com/notices/stock/600519.html");
        assertThat(itemsOf(result)).hasSize(2);
        assertThat(itemsOf(result).get(0).get("externalId")).isEqualTo("AN202608141827994406");
    }

    @Test
    void fetchPage_outOfBoundsPage_returnsOkEmptyItemsWithTotal_noCninfoFallback() {
        // Arrange：越界页（page 合法但超出源总页数）——东财返回 list=[] 且 total_hits=1074
        SourceResult result =
                fetchPage(5, 10, (east, cninfo) -> east.expect(requestTo(containsString("page_index=5")))
                        .andRespond(withSuccess(eastPageJson(5, 10, 1074, 0), MediaType.APPLICATION_JSON)));

        // Assert：200 空列表 + total 如实 + 仍标注可翻页；不触发巨潮兜底（cninfo server verify 已证零请求）
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getData().get("total")).isEqualTo(1074L);
        assertThat(result.getData().get("paginationSupported")).isEqualTo(true);
        assertThat(itemsOf(result)).isEmpty();
    }

    @Test
    void fetchPage_bypassesSourceCache_directSourceCallEachTime() {
        // Arrange：同一 adapter——先 fetch() 首屏（写快照缓存，同 subjectId 键控），再 fetchPage(page=2)
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyAnnounceClient(
                                eastBuilder, EAST_URL, 3, EAST_DETAIL_TEMPLATE, EAST_REFERER),
                        new CninfoAnnounceClient(
                                RestClient.builder(),
                                CNINFO_QUERY_URL,
                                CNINFO_STOCK_LIST_URL,
                                CNINFO_DETAIL_PREFIX,
                                ORG_ID_TTL));
        // 两页期望先全部注册再发请求（MockRestServiceServer 语义：期望须先于实际请求声明）
        eastServer.expect(requestTo(containsString("page_index=1")))
                .andRespond(withSuccess(eastPageJson(1, 10, 1074, 1), MediaType.APPLICATION_JSON));
        eastServer.expect(requestTo(containsString("page_index=2")))
                .andRespond(withSuccess(eastPageJson(2, 10, 1074, 1), MediaType.APPLICATION_JSON));
        SourceResult firstScreen = adapter.fetch(subject());
        SourceResult pageTwo = adapter.fetchPage(subject(), 2, 10);
        eastServer.verify();

        // Assert：翻页拿到源第 2 页真实数据（若命中首屏快照缓存，会返回第 1 页条目）
        assertThat(firstScreen.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(pageTwo.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(itemsOf(firstScreen).get(0).get("externalId"))
                .isEqualTo("AN202608141827994407");
        assertThat(itemsOf(pageTwo).get(0).get("externalId")).isEqualTo("AN202608141827994406");
    }

    // ---- fetchPage · 巨潮降级路径 ----

    @Test
    void fetchPage_eastMoneyFails_cninfoTakesOverPageOne_markedNotSupported() {
        // Arrange：东财 500 → 巨潮接住 page=1（orgId 表 + POST 查询）
        SourceResult result =
                fetchPage(1, 10, (east, cninfo) -> {
                    east.expect(requestTo(containsString(EAST_URL))).andRespond(withServerError());
                    cninfo.expect(requestTo(containsString(CNINFO_STOCK_LIST_URL)))
                            .andRespond(withSuccess(CNINFO_STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
                    cninfo.expect(requestTo(containsString(CNINFO_QUERY_URL)))
                            .andRespond(withSuccess(CNINFO_QUERY_JSON, MediaType.APPLICATION_JSON));
                });

        // Assert：巨潮第一页条目 + paginationSupported=false + total 不产出 + moreUrl 巨潮源站出口 + 来源标注备选
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告→巨潮资讯备选");
        assertThat(result.getData()).doesNotContainKey("total");
        assertThat(result.getData().get("paginationSupported")).isEqualTo(false);
        assertThat(result.getData().get("moreUrl"))
                .isEqualTo(
                        "https://www.cninfo.com.cn/new/fulltextSearch?notautosubmit=&keyWord=600519");
        assertThat(itemsOf(result)).hasSize(1);
    }

    @Test
    void fetchPage_eastMoneyFails_cninfoPageBeyondFirst_returnsEmptyWithNotSupported() {
        // Arrange：page=2 时东财恰好失败、巨潮接住 → 空列表 + paginationSupported:false（非错误，前端渲染降级文案）
        SourceResult result =
                fetchPage(2, 10, (east, cninfo) -> east.expect(requestTo(containsString(EAST_URL)))
                        .andRespond(withServerError()));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getData().get("paginationSupported")).isEqualTo(false);
        assertThat(result.getData()).doesNotContainKey("total");
        assertThat(itemsOf(result)).isEmpty();
    }

    @Test
    void fetchPage_allProvidersFail_degradesToMissing() {
        // Arrange：东财 500、巨潮 orgId 表 500 → 全链失败走弹性降级 MISSING（不抛出，不阻断）
        SourceResult result =
                fetchPage(1, 10, (east, cninfo) -> {
                    east.expect(requestTo(containsString(EAST_URL))).andRespond(withServerError());
                    cninfo.expect(requestTo(containsString(CNINFO_STOCK_LIST_URL)))
                            .andRespond(withServerError());
                });

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    // ---- doFetch · 首屏分页元数据（sectionPagination 提取口径） ----

    @Test
    void doFetch_firstScreen_carriesPaginationMetadataForSectionPagination() {
        // Arrange：首屏聚合路径（fetch → 缓存包装 doFetch）——data 附带 total/paginationSupported/moreUrl
        SourceResult result =
                fetchFirstScreen((east, cninfo) -> east.expect(requestTo(containsString("stock_list=600519")))
                        .andRespond(withSuccess(eastPageJson(1, 3, 1074, 1), MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getData().get("total")).isEqualTo(1074L);
        assertThat(result.getData().get("paginationSupported")).isEqualTo(true);
        assertThat(result.getData().get("moreUrl"))
                .isEqualTo("https://data.eastmoney.com/notices/stock/600519.html");
        assertThat(itemsOf(result)).hasSize(1);
    }

    // ---- helpers ----

    /** 东财分页响应夹具：totalHits 恒定（实测口径），itemCount 条公告（art_code 递减构造）。 */
    private static String eastPageJson(int pageIndex, int pageSize, long totalHits, int itemCount) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < itemCount; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append(
                    """
                    {"art_code":"AN20260814182799440%d",
                     "title":"贵州茅台:2026年半年度报告（第%d批）",
                     "notice_date":"2026-08-15 00:00:00","source_type":"31",
                     "codes":[{"short_name":"贵州茅台","stock_code":"600519"}],
                     "columns":[{"column_name":"其他"}]}"""
                            .formatted(7 - i - (pageIndex - 1), i + 1));
        }
        return "{\"data\":{\"list\":[" + items + "],\"page_index\":" + pageIndex
                + ",\"page_size\":" + pageSize + ",\"total_hits\":" + totalHits
                + "},\"error\":\"\",\"success\":1}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(SourceResult result) {
        return (List<Map<String, Object>>) result.getData().get("items");
    }

    /** 绑定东财/巨潮两台 mock server 构造 adapter 并执行 fetchPage（绕缓存路径）。 */
    private SourceResult fetchPage(
            int page, int size, BiConsumer<MockRestServiceServer, MockRestServiceServer> responses) {
        return withMockServers(
                responses, adapter -> adapter.fetchPage(subject(), page, size));
    }

    /** 首屏路径（fetch → 缓存包装 doFetch）。 */
    private SourceResult fetchFirstScreen(
            BiConsumer<MockRestServiceServer, MockRestServiceServer> responses) {
        return withMockServers(responses, adapter -> adapter.fetch(subject()));
    }

    private SourceResult withMockServers(
            BiConsumer<MockRestServiceServer, MockRestServiceServer> responses,
            Function<AnnounceSourceAdapter, SourceResult> action) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        RestClient.Builder cninfoBuilder = RestClient.builder();
        MockRestServiceServer cninfoServer = MockRestServiceServer.bindTo(cninfoBuilder).build();
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyAnnounceClient(
                                eastBuilder, EAST_URL, 3, EAST_DETAIL_TEMPLATE, EAST_REFERER),
                        new CninfoAnnounceClient(
                                cninfoBuilder,
                                CNINFO_QUERY_URL,
                                CNINFO_STOCK_LIST_URL,
                                CNINFO_DETAIL_PREFIX,
                                ORG_ID_TTL));
        responses.accept(eastServer, cninfoServer);
        SourceResult result = action.apply(adapter);
        eastServer.verify();
        cninfoServer.verify();
        return result;
    }

    private static Subject subject() {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("eastmoney", "1.600519", "tushare", "600519.SH"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
