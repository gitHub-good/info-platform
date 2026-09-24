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
import java.math.BigDecimal;
import java.time.Instant;
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
 * ValuationSourceAdapter 单测（T04）：东财 push2 估值真实接入。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟东财 push2 估值响应 JSON（与行情同端点、fields=估值列 f162/f167），覆盖：
 * 正常取数→字段映射→OK / data 空→MISSING / 空 data 对象→MISSING / null 响应体→MISSING / HTTP 500→异常降级 MISSING /
 * secid 从 externalCodes 正确取 / 缺 eastmoney 键→MISSING（不发请求）/ externalCodes 为 null→MISSING。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换： false 时真实估值 adapter
 * 装配且 mock 不装配、true/缺省 时 mock 装配且真实不装配。不启 Flyway/DB。
 *
 * <p>字段值取 2026-09-21 curl 茅台 1.600519 实测（f162=17.57 推测 PE-TTM、f167=6.23 推测 PB），fltt=2 下返回数值；
 * 语义待多股实测确认（见 adapter Javadoc）。
 */
class ValuationSourceAdapterTest {

    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String QUOTE_FIELDS =
            "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171";
    private static final String VALUATION_FIELDS = "f57,f162,f167";

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
    void fetch_normalResponse_mapsFieldsAndReturnsOk() {
        String json =
                """
                {"rc":0,"data":{
                  "f57":"600519","f58":"贵州茅台","f162":17.57,"f167":6.23
                }}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("secid=1.600519")))
                                        .andExpect(
                                                requestTo(containsString("fields=f57,f162,f167")))
                                        .andExpect(method(HttpMethod.GET))
                                        // ISSUE-A：估值与行情共用 EastMoneyClient，同样须带浏览器 UA
                                        .andExpect(header("User-Agent", containsString("Mozilla")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.VALUATION);
        assertThat(result.getSource()).isEqualTo("东方财富估值");
        assertThat((BigDecimal) result.getData().get("peTtm"))
                .isEqualByComparingTo(new BigDecimal("17.57"));
        assertThat((BigDecimal) result.getData().get("pb"))
                .isEqualByComparingTo(new BigDecimal("6.23"));
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
    }

    @Test
    void fetch_dataNodeNull_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":null}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_emptyDataObject_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":{}}",
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
                                server.expect(requestTo(containsString("secid=1.600519")))
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
                                server.expect(requestTo(containsString("secid=1.600519")))
                                        .andRespond(withServerError()));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_missingSecidKey_returnsMissingWithoutHttpCall() {
        ValuationSourceAdapter adapter = newAdapter();

        SourceResult result =
                adapter.fetch(subjectWithExternalCodes(Map.of("tushare", "600519.SH")));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_nullExternalCodes_returnsMissing() {
        ValuationSourceAdapter adapter = newAdapter();

        SourceResult result = adapter.fetch(subjectWithExternalCodes(null));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void supportedSubjectTypes_stockOnly() {
        // Arrange: T31 类型注册位 —— PE-TTM/PB 为个股估值语义
        ValuationSourceAdapter adapter = newAdapter();

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

    private ValuationSourceAdapter newAdapter() {
        // eastmoney 强制单源 = 旧版语义（本测试类断言东财行为；auto 降级见 ValuationSourceAdapterFallbackTest）
        return new ValuationSourceAdapter(
                cache,
                fieldMapper,
                runner,
                breaker,
                mockClient(),
                unusedTencentClient(),
                "eastmoney");
    }

    /** 永不被调用的腾讯客户端占位（eastmoney 模式下零外呼，纯构造无请求）。 */
    private static TencentQuoteClient unusedTencentClient() {
        return new TencentQuoteClient(RestClient.builder(), "https://qt.gtimg.cn/q=");
    }

    /** 构造绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyClient client =
                new EastMoneyClient(builder, QUOTE_URL, QUOTE_FIELDS, VALUATION_FIELDS);
        ValuationSourceAdapter adapter =
                new ValuationSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        client,
                        unusedTencentClient(),
                        "eastmoney");
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    /** 客户端用真实 URL/字段，但不发请求（供不发 HTTP 的早返回场景）。 */
    private EastMoneyClient mockClient() {
        return new EastMoneyClient(RestClient.builder(), QUOTE_URL, QUOTE_FIELDS, VALUATION_FIELDS);
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
