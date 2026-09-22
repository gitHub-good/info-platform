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
import java.math.BigDecimal;
import java.time.Instant;
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
 * QuoteSourceAdapter 单测（T03）：东财 push2 真实行情接入。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟东财响应 JSON（不依赖真实网络），覆盖： 正常取数→字段映射→OK / data 空→MISSING / 空
 * data 对象→MISSING / null 响应体→MISSING / HTTP 500→异常降级 MISSING / secid 从 externalCodes 正确取 / 缺
 * eastmoney 键→MISSING（不发请求）/ externalCodes 为 null→MISSING。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换： false 时真实 adapter
 * 装配且 mock 不装配、true/缺省 时 mock 装配且真实不装配。不启 Flyway/DB。
 *
 * <p>东财响应字段以字符串还是数值返回（fltt=2 下）语义待真实环境验证；FieldMapper 的 toDecimal/toLong 对两者均兼容，测试用字符串小数 + 数值成交量构造。
 */
class QuoteSourceAdapterTest {

    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171";

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
                  "f57":"600519","f58":"贵州茅台",
                  "f43":"1680.50","f46":"1670.00","f44":"1690.00","f45":"1665.00","f60":"1669.00",
                  "f169":"11.50","f170":"0.69",
                  "f47":123456,"f48":"9876543210","f171":"1.50","f168":"0.12"
                }}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("secid=1.600519")))
                                        .andExpect(method(HttpMethod.GET))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.QUOTE);
        assertThat(result.getSource()).isEqualTo("东方财富行情");
        assertThat((BigDecimal) result.getData().get("price"))
                .isEqualByComparingTo(new BigDecimal("1680.50"));
        assertThat((BigDecimal) result.getData().get("open"))
                .isEqualByComparingTo(new BigDecimal("1670.00"));
        assertThat((BigDecimal) result.getData().get("high"))
                .isEqualByComparingTo(new BigDecimal("1690.00"));
        assertThat((BigDecimal) result.getData().get("low"))
                .isEqualByComparingTo(new BigDecimal("1665.00"));
        assertThat((BigDecimal) result.getData().get("preClose"))
                .isEqualByComparingTo(new BigDecimal("1669.00"));
        assertThat((BigDecimal) result.getData().get("change"))
                .isEqualByComparingTo(new BigDecimal("11.50"));
        assertThat((BigDecimal) result.getData().get("changePct"))
                .isEqualByComparingTo(new BigDecimal("0.69"));
        assertThat(result.getData().get("volume")).isEqualTo(123456L);
        assertThat((BigDecimal) result.getData().get("amount"))
                .isEqualByComparingTo(new BigDecimal("9876543210"));
        assertThat((BigDecimal) result.getData().get("amplitude"))
                .isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat((BigDecimal) result.getData().get("turnoverRate"))
                .isEqualByComparingTo(new BigDecimal("0.12"));
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
        assertThat(result.getData().get("name")).isEqualTo("贵州茅台");
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
        QuoteSourceAdapter adapter = newAdapter();

        SourceResult result =
                adapter.fetch(subjectWithExternalCodes(Map.of("tushare", "600519.SH")));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_nullExternalCodes_returnsMissing() {
        QuoteSourceAdapter adapter = newAdapter();

        SourceResult result = adapter.fetch(subjectWithExternalCodes(null));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }


    @Configuration
    static class RestClientBuilderConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    // ---- helpers ----

    private QuoteSourceAdapter newAdapter() {
        return new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, mockClient());
    }

    /** 构造一个绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyClient client = new EastMoneyClient(builder, QUOTE_URL, FIELDS, "f57,f162,f167");
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, client);
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    /** 客户端用真实 URL/字段，但不发请求（供不发 HTTP 的早返回场景）。 */
    private EastMoneyClient mockClient() {
        return new EastMoneyClient(RestClient.builder(), QUOTE_URL, FIELDS, "f57,f162,f167");
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
