package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * QuoteSourceAdapter 腾讯备选降级单测（ADR-0031，修前红回归用例）：东财 push2 IP 封禁期间行情全站瘫痪—— 封禁签名（200 + 空 data / HTTP
 * 500）须自动降级腾讯 qt.gtimg.cn 重拉，恢复 OK 分区。
 *
 * <p>覆盖：auto 东财成功不碰腾讯 / 东财空响应（封禁签名）降级腾讯 OK 且来源标注「→腾讯备选」/ 东财 HTTP 错误降级 / 双失败沿既有弹性语义 MISSING / 双空响应
 * MISSING / 强制 tencent（东财零外呼）/ 强制 eastmoney（不降级，语义同旧版）/ 缺 eastmoney secid 时按内部码走腾讯 / 降级成功走缓存（腾讯单次外呼
 * + OK 心跳单次）/ 港股标的换算 hk 符号 / 非法配置值启动 fail-fast。
 */
class QuoteSourceAdapterFallbackTest {

    private static final String EAST_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String EAST_FIELDS =
            "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171";
    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=";

    private ExecutorService exec;
    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(new ObjectMapper());
        runner = new ResilienceRunner(exec);
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void auto_eastMoneyOk_tencentNeverCalled() {
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        eastQuoteJson(),
                                                        MediaType.APPLICATION_JSON)),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.tencent().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情");
    }

    @Test
    void auto_eastMoneyEmptyData_fallsBackToTencentWithFallbackLabel() {
        // 封禁签名：push2 200 + data:null（编排者 2026-09 实测空响应）
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":null}",
                                                        MediaType.APPLICATION_JSON)),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_SH600519.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情→腾讯备选");
        assertThat((BigDecimal) result.getData().get("price")).isEqualByComparingTo("1237.00");
        assertThat((BigDecimal) result.getData().get("preClose")).isEqualByComparingTo("1251.24");
        assertThat((BigDecimal) result.getData().get("changePct")).isEqualByComparingTo("-1.14");
        assertThat(result.getData().get("volume")).isEqualTo(31239L);
        assertThat((BigDecimal) result.getData().get("amount")).isEqualByComparingTo("3867310000");
        assertThat((BigDecimal) result.getData().get("turnoverRate")).isEqualByComparingTo("0.25");
        assertThat(result.getData().get("name")).isEqualTo("贵州茅台");
    }

    @Test
    void auto_eastMoneyHttpError_fallsBackToTencent() {
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(withServerError()),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_SH600519.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) result.getData().get("price")).isEqualByComparingTo("1237.00");
    }

    @Test
    void auto_bothHttpError_degradesToMissing() {
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(withServerError()),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(withServerError()));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        // 双失败：沿既有弹性语义降级 MISSING（不阻断聚合其他分区）
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void auto_bothEmpty_responsesReturnMissing() {
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":null}",
                                                        MediaType.APPLICATION_JSON)),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(withSuccess(new byte[0], MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void forcedTencent_eastMoneyNeverCalled_okFromTencent() {
        Bound bound =
                bind(
                        "tencent",
                        server -> {},
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_SH600519.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("腾讯行情");
        assertThat((BigDecimal) result.getData().get("price")).isEqualByComparingTo("1237.00");
    }

    @Test
    void forcedEastmoney_neverFallsBack() {
        // 强制单源 = 旧版语义：东财失败即 MISSING，不碰腾讯（排障口径，对齐 ADR-0030 模式）
        Bound bound =
                bind(
                        "eastmoney",
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(withServerError()),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.tencent().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    @Test
    void auto_missingSecid_fallsBackToTencentViaSubjectCode() {
        // externalCodes 无 eastmoney 键：东财路径零外呼，auto 仍可按内部码 SH600519 走腾讯
        Subject subject =
                Subject.reconstruct(
                        1L,
                        SubjectCode.of("SH600519"),
                        Market.A_SHARE,
                        SubjectType.STOCK,
                        "贵州茅台",
                        Map.of("tushare", "600519.SH"),
                        "白酒",
                        SubjectStatus.ENABLED,
                        1L,
                        Instant.parse("2026-09-20T00:00:00Z"),
                        Instant.parse("2026-09-20T00:00:00Z"));
        Bound bound =
                bind(
                        server -> {},
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_SH600519.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(subject);

        bound.east().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情→腾讯备选");
    }

    @Test
    void auto_fallbackOk_secondFetchServedFromCacheWithSingleHeartbeat() {
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=1.600519")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":null}",
                                                        MediaType.APPLICATION_JSON)),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "sh600519"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_SH600519.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));
        ReflectionTestUtils.setField(bound.adapter(), "dataSourceEventRecorder", recorder);

        SourceResult first = bound.adapter().fetch(aShareSubject());
        SourceResult second = bound.adapter().fetch(aShareSubject());

        // 降级成功按 OK 分区走正缓存（第二次命中缓存不再外呼——mock 服务端单期待 + verify 即证）
        assertThat(first.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) second.getData().get("price"))
                .isEqualByComparingTo((BigDecimal) first.getData().get("price"));
        bound.tencent().verify();
        // OK 心跳旁路：成功路径至多一次（缓存命中不触发）
        verify(recorder, times(1)).recordOkIfDue(eq(SourceCode.QUOTE), any());
    }

    @Test
    void auto_hkSubject_fallsBackWithHkSymbol() {
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=116.00700")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":null}",
                                                        MediaType.APPLICATION_JSON)),
                        server ->
                                server.expect(requestTo(TENCENT_URL + "hk00700"))
                                        .andRespond(
                                                withSuccess(
                                                        TencentQuoteFixtures.LINE_HK00700.getBytes(
                                                                TencentQuoteFixtures.GBK),
                                                        MediaType.TEXT_HTML)));

        SourceResult result = bound.adapter().fetch(hkSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) result.getData().get("price")).isEqualByComparingTo("438.400");
        assertThat(result.getData().get("volume")).isEqualTo(14277944L);
    }

    @Test
    void constructor_invalidMode_failsFast() {
        EastMoneyClient east =
                new EastMoneyClient(RestClient.builder(), EAST_URL, EAST_FIELDS, "f57,f162,f167");
        TencentQuoteClient tencent = new TencentQuoteClient(RestClient.builder(), TENCENT_URL);

        assertThatThrownBy(
                        () ->
                                new QuoteSourceAdapter(
                                        cache,
                                        fieldMapper,
                                        runner,
                                        new NoopCircuitBreaker(),
                                        east,
                                        tencent,
                                        "sina"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasource.QUOTE.params.backupSource");
    }

    // ---- helpers ----

    private static String eastQuoteJson() {
        return """
                {"rc":0,"data":{"f57":"600519","f58":"贵州茅台","f43":"1680.50","f46":"1670.00",
                  "f44":"1690.00","f45":"1665.00","f60":"1669.00","f169":"11.50","f170":"0.69",
                  "f47":123456,"f48":"9876543210","f171":"1.50","f168":"0.12"}}
                """;
    }

    private static Subject aShareSubject() {
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

    private static Subject hkSubject() {
        return Subject.reconstruct(
                2L,
                SubjectCode.of("HK00700"),
                Market.HK,
                SubjectType.STOCK,
                "腾讯控股",
                Map.of("eastmoney", "116.00700"),
                null,
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }

    /** 双客户端绑定结果：adapter + 两端 mock 服务端（可分别 verify 外呼次数）。 */
    private record Bound(
            QuoteSourceAdapter adapter,
            MockRestServiceServer east,
            MockRestServiceServer tencent) {}

    private Bound bind(
            java.util.function.Consumer<MockRestServiceServer> eastExpect,
            java.util.function.Consumer<MockRestServiceServer> tencentExpect) {
        return bind("auto", eastExpect, tencentExpect);
    }

    private Bound bind(
            String mode,
            java.util.function.Consumer<MockRestServiceServer> eastExpect,
            java.util.function.Consumer<MockRestServiceServer> tencentExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder tencentBuilder = RestClient.builder();
        MockRestServiceServer tencentServer = MockRestServiceServer.bindTo(tencentBuilder).build();
        tencentExpect.accept(tencentServer);
        EastMoneyClient east =
                new EastMoneyClient(eastBuilder, EAST_URL, EAST_FIELDS, "f57,f162,f167");
        TencentQuoteClient tencent = new TencentQuoteClient(tencentBuilder, TENCENT_URL);
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(
                        cache, fieldMapper, runner, new NoopCircuitBreaker(), east, tencent, mode);
        return new Bound(adapter, eastServer, tencentServer);
    }
}
