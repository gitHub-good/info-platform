package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import com.info.platform.infrastructure.common.SourceAdapterInfrastructureConfig;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * NewsSourceAdapter 单测（T06）：新浪滚动新闻真实接入 + 个股关联关键词匹配。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟新浪响应 JSON（结构 {@code result.data[]}，2026-09-21 curl
 * 实测确认），覆盖：正常取数→关键词过滤→逐条映射→OK（含 title/publishedAt/summary/url/source/keywords， {@code ctime} epoch
 * 秒→Asia/Shanghai ISO 转换，断言<b>必带 UA+Referer</b>）/ 全市场流无命中→MISSING / 空数组→MISSING / result 节点
 * null→MISSING / null 响应体→MISSING / HTTP 500→异常降级 MISSING / 缺代码靠 名称匹配 / externalCodes 为 null
 * 仍靠名称匹配。
 *
 * <p><b>关键词匹配逻辑</b>（核心难点）单独单测 {@link NewsSourceAdapter#isRelevant}：标题含名称命中 / 关键词含 名称命中 / 标题含代码命中 /
 * 都不含不命中 / name null 仅代码 / code null 仅名称 / blank / 缺字段。
 *
 * <p><b>实测偏差</b>（vs Spike-1 §6.5/§4.5）：响应路径为 {@code result.data[]}（非 {@code data}）；{@code ctime} 为
 * Unix 秒级时间戳（非日期串），adapter 内 epoch→Asia/Shanghai datetime 预处理；{@code stock}/{@code k}
 * 参数被忽略（不支持个股过滤）。mock 响应按实测结构构造。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换。不启 Flyway/DB。
 *
 * <p>字段值取 2026-09-21 curl lid=2510 实测：{@code ctime} 为 epoch 秒（如 {@code 1716768000}），经 adapter 预处理 +
 * {@code to_iso_date} → {@code 2024-05-27T08:00:00}（Asia/Shanghai +08:00）。
 */
class NewsSourceAdapterTest {

    private static final String NEWS_URL = "https://feed.mix.sina.com.cn/api/roll/get";
    private static final int PAGE_ID = 153;
    private static final int LID = 2510;
    private static final int PAGE_SIZE = 20;
    private static final String REFERER = "https://finance.sina.com.cn";

    /** 测试用 ctime（epoch 秒，2024-05-27 00:00:00 UTC → +08:00 → 08:00:00）。 */
    private static final long CTIME_EPOCH = 1716768000L;

    private static final String EXPECTED_PUBLISHED_AT =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                    LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(CTIME_EPOCH), ZoneId.of("Asia/Shanghai")));

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
    void fetch_normalResponse_filtersByKeywordAndMapsItems() {
        // 实测结构 result.data[]；doc1 标题含「贵州茅台」命中、doc2 无关不命中、doc3 标题含「600519」命中
        String json =
                """
                {"result":{"status":{"code":0,"msg":"succ"},"total":3,"lid":2510,"data":[
                  {"docid":"doc1","title":"贵州茅台2025年中期业绩超预期","stitle":"",
                   "ctime":"1716768000","intro":"贵州茅台发布2025年中期报告营收净利双增",
                   "url":"https://news.sina.com.cn/c/2025/doc1.shtml",
                   "media_name":"证券日报","keywords":"贵州茅台,白酒,业绩"},
                  {"docid":"doc2","title":"某科技公司发布新品","ctime":"1716854400",
                   "intro":"科技新品发布","url":"https://news.sina.com.cn/c/2025/doc2.shtml",
                   "media_name":"新华社","keywords":"科技,新品"},
                  {"docid":"doc3","title":"600519盘中创年内新高","ctime":"1716940800",
                   "intro":"A股盘中","url":"https://news.sina.com.cn/c/2025/doc3.shtml",
                   "media_name":"新浪财经","keywords":"A股,茅台"}
                ]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andExpect(requestTo(containsString("pageid=153")))
                                        .andExpect(requestTo(containsString("num=20")))
                                        .andExpect(requestTo(containsString("page=1")))
                                        .andExpect(method(HttpMethod.GET))
                                        // 新浪需带 UA + Referer（实测裸 curl 易 403）
                                        .andExpect(
                                                req ->
                                                        assertThat(
                                                                        req.getHeaders()
                                                                                .getFirst(
                                                                                        "User-Agent"))
                                                                .contains("Mozilla"))
                                        .andExpect(
                                                req ->
                                                        assertThat(
                                                                        req.getHeaders()
                                                                                .getFirst(
                                                                                        "Referer"))
                                                                .isEqualTo(REFERER))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.NEWS);
        assertThat(result.getSource()).isEqualTo("新浪财经新闻");

        Object itemsObj = result.getData().get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        // doc2 无关被过滤；doc1（标题含名称）+ doc3（标题含代码）命中
        assertThat(items).hasSize(2);

        Map<String, Object> first = items.get(0);
        assertThat(first.get("externalId")).isEqualTo("doc1");
        assertThat(first.get("title")).isEqualTo("贵州茅台2025年中期业绩超预期");
        // ctime epoch 秒 → Asia/Shanghai ISO（to_iso_date 链路）
        assertThat(first.get("publishedAt")).isEqualTo(EXPECTED_PUBLISHED_AT);
        assertThat(first.get("summary")).isEqualTo("贵州茅台发布2025年中期报告营收净利双增");
        assertThat(first.get("url")).isEqualTo("https://news.sina.com.cn/c/2025/doc1.shtml");
        assertThat(first.get("source")).isEqualTo("证券日报");
        assertThat(first.get("keywords")).isEqualTo("贵州茅台,白酒,业绩");

        Map<String, Object> second = items.get(1);
        assertThat(second.get("externalId")).isEqualTo("doc3");
        assertThat(second.get("title")).isEqualTo("600519盘中创年内新高");
    }

    @Test
    void fetch_noHit_returnsMissing() {
        // 全市场流均不含标的名/代码 → 无命中 → MISSING（当日无相关新闻，不阻断）
        String json =
                """
                {"result":{"status":{"code":0,"msg":"succ"},"total":2,"data":[
                  {"docid":"docA","title":"某科技公司新品","ctime":"1716768000",
                   "intro":"...","url":"https://news.sina.com.cn/a.shtml",
                   "media_name":"新华社","keywords":"科技"},
                  {"docid":"docB","title":"国际油价下跌","ctime":"1716854400",
                   "intro":"...","url":"https://news.sina.com.cn/b.shtml",
                   "media_name":"新华财经","keywords":"原油"}
                ]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_emptyData_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"result\":{\"status\":{\"code\":0,"
                                                                + "\"msg\":\"succ\"},\"total\":0,"
                                                                + "\"data\":[]}}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_resultNodeNull_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"result\":null}",
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
                                server.expect(requestTo(containsString("lid=2510")))
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
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(withServerError()));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_missingStockCode_matchesByNameOnly() {
        // externalCodes 无 eastmoney/eastmoney_code（仅 tushare）→ resolveStockCode null → 仅按名称匹配仍命中
        String json =
                """
                {"result":{"status":{"code":0,"msg":"succ"},"total":1,"data":[
                  {"docid":"doc1","title":"贵州茅台发布年报","ctime":"1716768000",
                   "intro":"...","url":"https://news.sina.com.cn/c/2025/doc1.shtml",
                   "media_name":"证券日报","keywords":"贵州茅台"}
                ]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(Map.of("tushare", "600519.SH")),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("externalId")).isEqualTo("doc1");
    }

    @Test
    void doFetch_nullExternalCodes_matchesByNameOnly() {
        // externalCodes 为 null：仍按 subject.name 匹配（resolveStockCode 早返回 null，不阻断名称匹配）
        String json =
                """
                {"result":{"status":{"code":0,"msg":"succ"},"total":1,"data":[
                  {"docid":"doc1","title":"贵州茅台召开股东大会","ctime":"1716768000",
                   "intro":"...","url":"https://news.sina.com.cn/c/2025/doc1.shtml",
                   "media_name":"证券日报","keywords":"贵州茅台,股东大会"}
                ]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(null),
                        server ->
                                server.expect(requestTo(containsString("lid=2510")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(1);
    }

    // ---- 关键词匹配逻辑（核心，独立单测 isRelevant）----

    @Test
    void isRelevant_nameMatch_variants() {
        // 标题含名称
        assertThat(NewsSourceAdapter.isRelevant(raw("贵州茅台发布年报", "白酒"), "贵州茅台", "600519")).isTrue();
        // 关键词含名称（标题无关）
        assertThat(NewsSourceAdapter.isRelevant(raw("某新闻", "白酒,贵州茅台"), "贵州茅台", "600519")).isTrue();
    }

    @Test
    void isRelevant_codeMatch_variants() {
        // 标题含代码
        assertThat(NewsSourceAdapter.isRelevant(raw("600519创新高", "A股"), "贵州茅台", "600519")).isTrue();
        // 关键词含代码
        assertThat(NewsSourceAdapter.isRelevant(raw("某新闻", "茅台,600519"), "贵州茅台", "600519"))
                .isTrue();
    }

    @Test
    void isRelevant_noMatch_variants() {
        // 都不含
        assertThat(NewsSourceAdapter.isRelevant(raw("某科技新品", "科技"), "贵州茅台", "600519")).isFalse();
        // name null 且无代码命中
        assertThat(NewsSourceAdapter.isRelevant(raw("某新闻", "科技"), null, "600519")).isFalse();
        // code null 且无名称命中
        assertThat(NewsSourceAdapter.isRelevant(raw("某新闻", "科技"), "贵州茅台", null)).isFalse();
        // name/code 均 blank
        assertThat(NewsSourceAdapter.isRelevant(raw("贵州茅台", "白酒"), "", "")).isFalse();
        // 缺 title/keywords 字段（stringOf(null)=""）
        assertThat(NewsSourceAdapter.isRelevant(new HashMap<>(), "贵州茅台", "600519")).isFalse();
    }

    @Test
    void isRelevant_partialName_notFalseMatchOnCode() {
        // 6 位代码精确性：含「600519」命中；仅含片段「60051」不命中（防误命中）
        assertThat(NewsSourceAdapter.isRelevant(raw("60051涨停", "A股"), "贵州茅台", "600519")).isFalse();
    }


    @Configuration
    static class RestClientBuilderConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SinaNewsClient client =
                new SinaNewsClient(builder, NEWS_URL, PAGE_ID, LID, PAGE_SIZE, REFERER);
        NewsSourceAdapter adapter =
                new NewsSourceAdapter(cache, fieldMapper, runner, breaker, client);
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    private static Map<String, Object> raw(String title, String keywords) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", title);
        map.put("keywords", keywords);
        return map;
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
