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
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AnnounceSourceAdapter 单测（T05）：东财 {@code np-anotice-stock} 公告真实接入。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟公告响应 JSON（结构 {@code data.list[]}，2026-09-21 curl
 * 实测确认），覆盖： 正常取数→拍平嵌套→逐条映射→OK（含
 * title/publishedAt/category/url/externalId/externalCode/subjectName，且断言<b>带 UA + 东财站内
 * Referer</b>，ISSUE-A）/ text/plain JSON 体→OK（ISSUE-B）/ 空列表→MISSING / data 节点 null→MISSING / null
 * 响应体→MISSING / HTTP 500→异常降级 MISSING / 缺 eastmoney 与 eastmoney_code→MISSING（不发请求）/ externalCodes 为
 * null→MISSING / 从 secid 派生 6 位代码 / 优先 eastmoney_code 键。
 *
 * <p><b>字段嵌套偏差</b>（vs Spike-1 §4.4）：实测 {@code stock_code}/{@code short_name} 嵌在 {@code codes[0]}、
 * {@code column_name} 嵌在 {@code columns[0]}（非列表项顶层）。mock 响应按实测结构构造，验证 adapter 拍平逻辑。响应路径 {@code
 * data.list} 与 Spike-1 §6.4 一致（无偏差，与 datacenter 财务 {@code result.data[0]} 偏差不同）。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换。不启 Flyway/DB。
 *
 * <p>字段值取 2026-09-21 curl 茅台 600519 实测：{@code notice_date} 为 {@code yyyy-MM-dd HH:mm:ss}，经 {@link
 * FieldMapper} {@code to_iso_date} → {@code 2026-08-15T00:00:00}；详情 URL 由 {@code art_code} 拼装
 * {@code https://pdf.dfcfw.com/pdf/H2_<art_code>_1.pdf}（实测稳定，200 application/pdf）。
 */
class AnnounceSourceAdapterTest {

    private static final String ANNOUNCE_URL =
            "https://np-anotice-stock.eastmoney.com/api/security/ann";
    private static final String ANNOUNCE_REFERER = "https://data.eastmoney.com/";
    private static final int PAGE_SIZE = 3;
    private static final String DETAIL_URL_TEMPLATE =
            "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf";

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

    @Test
    void fetch_normalResponse_mapsItemsAndReturnsOk() {
        // 实测结构：data.list[]，每条含顶层 art_code/title/notice_date + 嵌套 codes[0]/columns[0]
        String json =
                """
                {"data":{"list":[
                  {"art_code":"AN202608141827994407",
                   "title":"贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告",
                   "title_ch":"贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告","title_en":"",
                   "notice_date":"2026-08-15 00:00:00","display_time":"2026-08-14 20:41:29:276",
                   "sort_date":"2026-08-15 12:00:00","source_type":"31",
                   "codes":[{"ann_type":"A,SHA","inner_code":"46077278941243","market_code":"1",
                             "short_name":"贵州茅台","stock_code":"600519"}],
                   "columns":[{"column_code":"001002008","column_name":"其他"}]},
                  {"art_code":"AN202608141827994403",
                   "title":"贵州茅台:贵州茅台2026年半年度报告摘要",
                   "notice_date":"2026-08-15 00:00:00",
                   "codes":[{"short_name":"贵州茅台","stock_code":"600519"}],
                   "columns":[{"column_name":"半年度报告摘要"}]}
                ],"page_index":1,"page_size":3,"total_hits":1074},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andExpect(requestTo(containsString("sr=-1")))
                                        .andExpect(requestTo(containsString("page_size=3")))
                                        .andExpect(requestTo(containsString("ann_type=A")))
                                        .andExpect(requestTo(containsString("client_source=web")))
                                        .andExpect(method(HttpMethod.GET))
                                        // ISSUE-A：公告端点 WAF 收紧——须带浏览器 UA + 东财站内 Referer
                                        .andExpect(header("User-Agent", containsString("Mozilla")))
                                        .andExpect(header("Referer", ANNOUNCE_REFERER))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.ANNOUNCE);
        assertThat(result.getSource()).isEqualTo("东方财富公告");

        Object itemsObj = result.getData().get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        assertThat(items).hasSize(2);

        Map<String, Object> first = items.get(0);
        assertThat(first.get("externalId")).isEqualTo("AN202608141827994407");
        assertThat(first.get("title")).isEqualTo("贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告");
        assertThat(first.get("publishedAt")).isEqualTo("2026-08-15T00:00:00");
        assertThat(first.get("category")).isEqualTo("其他");
        assertThat(first.get("externalCode")).isEqualTo("600519");
        assertThat(first.get("subjectName")).isEqualTo("贵州茅台");
        assertThat(first.get("url"))
                .isEqualTo("https://pdf.dfcfw.com/pdf/H2_AN202608141827994407_1.pdf");

        Map<String, Object> second = items.get(1);
        assertThat(second.get("externalId")).isEqualTo("AN202608141827994403");
        assertThat(second.get("category")).isEqualTo("半年度报告摘要");
        assertThat(second.get("url"))
                .isEqualTo("https://pdf.dfcfw.com/pdf/H2_AN202608141827994403_1.pdf");
    }

    @Test
    void fetch_textPlainJsonBody_mapsItemsAndReturnsOk() {
        // ISSUE-B：np-anotice 实测返回 200 + text/plain;charset=UTF-8 的 JSON 体，
        // 须能解析为 Map 而非 UnknownContentTypeException（→ 降级 MISSING）
        String json =
                """
                {"data":{"list":[
                  {"art_code":"AN202608141827994407",
                   "title":"贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告",
                   "notice_date":"2026-08-15 00:00:00","source_type":"31",
                   "codes":[{"short_name":"贵州茅台","stock_code":"600519"}],
                   "columns":[{"column_name":"其他"}]}
                ],"page_index":1,"page_size":3,"total_hits":1074},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withSuccess(json, MediaType.TEXT_PLAIN)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        Object itemsObj = result.getData().get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("externalId")).isEqualTo("AN202608141827994407");
    }

    @Test
    void fetch_emptyList_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"data\":{\"list\":[],\"page_index\":1,"
                                                                + "\"page_size\":3,\"total_hits\":0},"
                                                                + "\"error\":\"\",\"success\":1}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_dataWithoutListKey_returnsMissing() {
        // 防御：data 是 Map 但缺 list 键（eastmoney 异常返回可能的形态）→ list=null → MISSING
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"data\":{\"page_index\":1,"
                                                                + "\"page_size\":3,\"total_hits\":0},"
                                                                + "\"error\":\"\",\"success\":1}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_dataNodeNull_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"data\":null,\"error\":\"\",\"success\":1}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_nullBody_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess("null", MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_http500_throwsAndDegradesToMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withServerError()));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_missingCodeAndSecid_returnsMissingWithoutHttpCall() {
        AnnounceSourceAdapter adapter = newAdapter();

        SourceResult result =
                adapter.fetch(subjectWithExternalCodes(Map.of("tushare", "600519.SH")));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_nullExternalCodes_returnsMissing() {
        AnnounceSourceAdapter adapter = newAdapter();

        SourceResult result = adapter.fetch(subjectWithExternalCodes(null));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_itemMissingNestedFields_mapsTopLevelAndOmitsAbsent() {
        // 防御：某条公告缺 codes[]/columns[]（实测正常返回均有，此处覆盖 flatten 的 instanceof false 分支）
        String json =
                """
                {"data":{"list":[{"art_code":"AN202608141827994407","title":"无分类公告",
                  "notice_date":"2026-08-15 00:00:00"}]},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        // 顶层字段仍映射成功
        assertThat(item.get("externalId")).isEqualTo("AN202608141827994407");
        assertThat(item.get("title")).isEqualTo("无分类公告");
        assertThat(item.get("publishedAt")).isEqualTo("2026-08-15T00:00:00");
        // url 仍由 art_code 拼装
        assertThat(item.get("url"))
                .isEqualTo("https://pdf.dfcfw.com/pdf/H2_AN202608141827994407_1.pdf");
        // 嵌套缺失字段不产出（白名单语义）
        assertThat(item).doesNotContainKey("externalCode");
        assertThat(item).doesNotContainKey("subjectName");
        assertThat(item).doesNotContainKey("category");
    }

    @Test
    void doFetch_secidWithoutDot_usesWholeSecidAsCode() {
        // 防御：secid 无 '.' 时整体作为 6 位代码（resolveStockCode 的 dot<0 分支）
        String json =
                """
                {"data":{"list":[{"art_code":"AN202608141827994407","title":"测试",
                  "notice_date":"2026-08-15 00:00:00",
                  "codes":[{"stock_code":"600519","short_name":"贵州茅台"}],
                  "columns":[{"column_name":"其他"}]}]},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(Map.of("eastmoney", "600519")),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items.get(0).get("externalCode")).isEqualTo("600519");
    }

    @Test
    void fetch_emptyNestedArraysAndNullValues_omitsAbsentFields() {
        // 防御：codes[0] 有 stock_code 但 short_name=null；columns=[] 空 → 部分嵌套字段缺省不产出
        // 覆盖 flatten 的 !list.isEmpty() false 与 putIfPresent value==null false 分支
        String json =
                """
                {"data":{"list":[{"art_code":"AN202608141827994407","title":"部分字段公告",
                  "notice_date":"2026-08-15 00:00:00",
                  "codes":[{"stock_code":"600519","short_name":null}],
                  "columns":[]}]},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        Map<String, Object> item = items.get(0);
        assertThat(item.get("externalCode")).isEqualTo("600519");
        // short_name=null 与 columns=[] 空导致 subjectName/category 不产出（白名单语义）
        assertThat(item).doesNotContainKey("subjectName");
        assertThat(item).doesNotContainKey("category");
        assertThat(item.get("url"))
                .isEqualTo("https://pdf.dfcfw.com/pdf/H2_AN202608141827994407_1.pdf");
    }

    @Test
    void doFetch_prefersEastmoneyCodeKey_overSecidDerivation() {
        // 仅 eastmoney_code、无 eastmoney secid：直接用 600519，不派生
        String json =
                """
                {"data":{"list":[{"art_code":"AN202608141827994407","title":"测试公告",
                  "notice_date":"2026-08-15 00:00:00",
                  "codes":[{"stock_code":"600519","short_name":"贵州茅台"}],
                  "columns":[{"column_name":"其他"}]}]},"error":"","success":1}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(Map.of("eastmoney_code", "600519")),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("externalCode")).isEqualTo("600519");
    }

    @Test
    void supportedSubjectTypes_stockOnly() {
        // Arrange: T31 类型注册位 —— np-anotice-stock 为上市公司公告端点
        AnnounceSourceAdapter adapter = newAdapter();

        // Act
        Set<SubjectType> supported = adapter.supportedSubjectTypes();

        // Assert: 仅股票；指数/板块/预留类型（基金/债券）不进本源取数
        assertThat(supported).containsExactly(SubjectType.STOCK);
    }

    @Configuration
    static class RestClientBuilderConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    // ---- helpers ----

    private AnnounceSourceAdapter newAdapter() {
        return new AnnounceSourceAdapter(
                cache, fieldMapper, runner, breaker, mockClient(), mockCninfoClient());
    }

    /** 构造绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyAnnounceClient client =
                new EastMoneyAnnounceClient(
                        builder, ANNOUNCE_URL, PAGE_SIZE, DETAIL_URL_TEMPLATE, ANNOUNCE_REFERER);
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        cache, fieldMapper, runner, breaker, client, mockCninfoClient());
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    /** 客户端用真实 URL/参数，但不发请求（供不发 HTTP 的早返回场景）。 */
    private EastMoneyAnnounceClient mockClient() {
        return new EastMoneyAnnounceClient(
                RestClient.builder(),
                ANNOUNCE_URL,
                PAGE_SIZE,
                DETAIL_URL_TEMPLATE,
                ANNOUNCE_REFERER);
    }

    /** 巨潮备选客户端替身（本测试类聚焦东财路径；巨潮路径见 AnnounceSourceAdapterFallbackTest）。 */
    private static CninfoAnnounceClient mockCninfoClient() {
        return new CninfoAnnounceClient(
                RestClient.builder(),
                "https://cninfo.test/new/hisAnnouncement/query",
                "https://cninfo.test/new/data/szse_stock.json",
                "https://static.cninfo.test/",
                java.time.Duration.ofHours(24));
    }

    private static Subject subjectWithSecid(String secid) {
        return subjectWithExternalCodes(Map.of("eastmoney", secid, "tushare", "600519.SH"));
    }

    private static Subject subjectWithExternalCodes(Map<String, String> externalCodes) {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                externalCodes,
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
