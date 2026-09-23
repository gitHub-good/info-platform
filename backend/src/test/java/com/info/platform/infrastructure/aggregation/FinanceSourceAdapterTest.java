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
 * FinanceSourceAdapter 单测（T04）：东财 datacenter F10 财务真实接入。
 *
 * <p>行为测试用 {@link MockRestServiceServer} 模拟东财 datacenter 响应 JSON（结构 {@code result.data[0]}，
 * 2026-09-21 curl 实测确认），覆盖： 正常取数→字段映射→OK（含 Referer 头与请求参数校验）/ 空数组→MISSING / result 为 null→MISSING /
 * null 响应体→MISSING / HTTP 500→异常降级 MISSING / 缺 eastmoney 与 eastmoney_code→MISSING（不发请求）/
 * externalCodes 为 null→MISSING / 从 secid 派生 6 位代码 / 优先 eastmoney_code 键。
 *
 * <p>装配测试用 {@link ApplicationContextRunner} 验证 {@code adapter.mock.enabled} 切换。不启 Flyway/DB。
 *
 * <p>字段值取 2026-09-21 curl 茅台 600519 实测（TOTALOPERATEREVE/PARENTNETPROFIT/XSJLL/XSMLL/ROEJQ
 * 为数值、REPORT_DATE 为 yyyy-MM-dd HH:mm:ss）。响应结构为 {@code result.data[0]}（实测；Spike-1 §6.2 载 {@code
 * data.list[0]} 与实跑不符，本测试按实测）。
 */
class FinanceSourceAdapterTest {

    private static final String FINANCE_URL =
            "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String REFERER = "https://data.eastmoney.com/";

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
                {"result":{"pages":103,"data":[{
                  "SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
                  "REPORT_DATE":"2026-06-30 00:00:00",
                  "TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,
                  "XSJLL":50.7515717487,"XSMLL":89.5552128279,"ROEJQ":16.75
                }]}}
                """;
        // eastmoney secid 1.600519 → 派生 6 位代码 600519
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(
                                Map.of("eastmoney", "1.600519", "tushare", "600519.SH")),
                        server ->
                                server.expect(
                                                requestTo(
                                                        containsString(
                                                                "reportName=RPT_F10_FINANCE_MAINFINADATA")))
                                        .andExpect(requestTo(containsString("SECURITY_CODE")))
                                        .andExpect(requestTo(containsString("600519")))
                                        .andExpect(method(HttpMethod.GET))
                                        // ISSUE-A：东财 WAF 对无 UA 请求断连，须带浏览器 UA
                                        .andExpect(header("User-Agent", containsString("Mozilla")))
                                        .andExpect(header("Referer", REFERER))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.FINANCE);
        assertThat(result.getSource()).isEqualTo("东方财富财务");
        assertThat((BigDecimal) result.getData().get("revenue"))
                .isEqualByComparingTo(new BigDecimal("92278072083.21"));
        assertThat((BigDecimal) result.getData().get("netProfit"))
                .isEqualByComparingTo(new BigDecimal("44516880421.86"));
        assertThat((BigDecimal) result.getData().get("netProfitMargin"))
                .isEqualByComparingTo(new BigDecimal("50.7515717487"));
        assertThat((BigDecimal) result.getData().get("grossProfitMargin"))
                .isEqualByComparingTo(new BigDecimal("89.5552128279"));
        assertThat((BigDecimal) result.getData().get("roe"))
                .isEqualByComparingTo(new BigDecimal("16.75"));
        assertThat(result.getData().get("reportDate")).isEqualTo("2026-06-30T00:00:00");
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
    }

    @Test
    void fetch_textPlainJsonBody_mapsFieldsAndReturnsOk() {
        // ISSUE-B：datacenter 实测返回 200 + text/plain;charset=UTF-8 的 JSON 体，
        // 须能解析为 Map 而非 UnknownContentTypeException（→ 降级 MISSING）
        String json =
                """
                {"result":{"pages":103,"data":[{
                  "SECURITY_CODE":"600519","REPORT_DATE":"2026-06-30 00:00:00",
                  "TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,
                  "XSJLL":50.75,"XSMLL":89.56,"ROEJQ":16.75
                }]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("SECURITY_CODE")))
                                        .andRespond(withSuccess(json, MediaType.TEXT_PLAIN)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) result.getData().get("roe"))
                .isEqualByComparingTo(new BigDecimal("16.75"));
    }

    @Test
    void fetch_emptyDataList_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("SECURITY_CODE")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"result\":{\"pages\":0,\"data\":[]}}",
                                                        MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void fetch_resultNull_returnsMissing() {
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithSecid("1.600519"),
                        server ->
                                server.expect(requestTo(containsString("SECURITY_CODE")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"result\":null,\"data\":null,\"success\":true}",
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
                                server.expect(requestTo(containsString("SECURITY_CODE")))
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
                                server.expect(requestTo(containsString("SECURITY_CODE")))
                                        .andRespond(withServerError()));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_missingCodeAndSecid_returnsMissingWithoutHttpCall() {
        FinanceSourceAdapter adapter = newAdapter();

        SourceResult result =
                adapter.fetch(subjectWithExternalCodes(Map.of("tushare", "600519.SH")));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_nullExternalCodes_returnsMissing() {
        FinanceSourceAdapter adapter = newAdapter();

        SourceResult result = adapter.fetch(subjectWithExternalCodes(null));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void doFetch_prefersEastmoneyCodeKey_overSecidDerivation() {
        // 仅 eastmoney_code、无 eastmoney secid：直接用 600519，不派生
        String json =
                """
                {"result":{"pages":1,"data":[{
                  "SECURITY_CODE":"600519","REPORT_DATE":"2026-06-30 00:00:00",
                  "TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,
                  "XSJLL":50.75,"XSMLL":89.56,"ROEJQ":16.75
                }]}}
                """;
        SourceResult result =
                fetchWithMockResponse(
                        subjectWithExternalCodes(Map.of("eastmoney_code", "600519")),
                        server ->
                                server.expect(requestTo(containsString("600519")))
                                        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON)));

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
    }

    @Test
    void supportedSubjectTypes_stockOnly() {
        // Arrange: T31 类型注册位 —— F10 主财务指标为上市公司专属
        FinanceSourceAdapter adapter = newAdapter();

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

    private FinanceSourceAdapter newAdapter() {
        return new FinanceSourceAdapter(cache, fieldMapper, runner, breaker, mockClient());
    }

    /** 构造绑定 MockRestServiceServer 的客户端；响应由 setter 设置。 */
    private SourceResult fetchWithMockResponse(
            Subject subject, Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastMoneyFinanceClient client = new EastMoneyFinanceClient(builder, FINANCE_URL, REFERER);
        FinanceSourceAdapter adapter =
                new FinanceSourceAdapter(cache, fieldMapper, runner, breaker, client);
        responseSetter.accept(server);
        SourceResult result = adapter.fetch(subject);
        server.verify();
        return result;
    }

    /** 客户端用真实 URL/Referer，但不发请求（供不发 HTTP 的早返回场景）。 */
    private EastMoneyFinanceClient mockClient() {
        return new EastMoneyFinanceClient(RestClient.builder(), FINANCE_URL, REFERER);
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
