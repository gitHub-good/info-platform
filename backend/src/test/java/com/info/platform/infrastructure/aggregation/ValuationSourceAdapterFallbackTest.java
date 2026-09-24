package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ValuationSourceAdapter 腾讯备选降级单测（ADR-0031，修前红回归用例）：东财 push2 封禁期间估值分区瘫痪—— 空响应/HTTP 错误自动降级腾讯，PE/PB
 * 从腾讯字段映射（A股 PE@52/PB@46，港股 PE@39/PB@58，索引见 TencentQuoteClient Javadoc 核对表）。
 *
 * <p>覆盖：auto 东财成功不碰腾讯 / 东财空响应降级腾讯 OK（peTtm/pb 映射 + 来源标注）/ 港股降级（东财 HK f162='-' 缺失由腾讯补齐）/ 双失败 MISSING
 * / 强制单源两向 / 非法配置值 fail-fast。
 */
class ValuationSourceAdapterFallbackTest {

    private static final String EAST_URL = "https://push2.eastmoney.com/api/qt/stock/get";
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
                                                        "{\"rc\":0,\"data\":{\"f57\":\"600519\",\"f162\":17.37,"
                                                                + "\"f167\":6.15}}",
                                                        MediaType.APPLICATION_JSON)),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.tencent().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富估值");
        assertThat((BigDecimal) result.getData().get("peTtm")).isEqualByComparingTo("17.37");
        assertThat((BigDecimal) result.getData().get("pb")).isEqualByComparingTo("6.15");
    }

    @Test
    void auto_eastMoneyEmptyData_fallsBackToTencentWithPePb() {
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
        assertThat(result.getSource()).isEqualTo("东方财富估值→腾讯备选");
        // A股：PE 取腾讯 52 位（17.37 与东财 f162 数值一致）、PB 取 46 位（6.15 与东财 f167 一致）——2026-09-24 双样本交叉核对
        assertThat((BigDecimal) result.getData().get("peTtm")).isEqualByComparingTo("17.37");
        assertThat((BigDecimal) result.getData().get("pb")).isEqualByComparingTo("6.15");
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
    }

    @Test
    void auto_hkSubject_fallsBackWithHkValuationIndexes() {
        // 东财封禁签名（空 data）→ 腾讯港股位映射：PE@39 / PB@58（A 股位 52/46 对港股行是别的字段，见核对表）
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
        assertThat(result.getSource()).isEqualTo("东方财富估值→腾讯备选");
        assertThat((BigDecimal) result.getData().get("peTtm")).isEqualByComparingTo("16.02");
        assertThat((BigDecimal) result.getData().get("pb")).isEqualByComparingTo("3.07");
    }

    @Test
    void eastMoneyDashValue_degradesToFieldMissing_notMappingException() {
        // 修前红（ADR-0031 发现修复）：东财港股估值 f162 实测返回 '-'（2026-09-24 curl 116.00700），
        // 修前 toDecimal("-") 抛 FieldMappingException 使估值分区异常；修后 '-' 归一为字段缺失——OK 仅产出 pb
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "secid=116.00700")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"rc\":0,\"data\":{\"f57\":\"00700\",\"f162\":\"-\",\"f167\":3.05}}",
                                                        MediaType.APPLICATION_JSON)),
                        server -> {});

        SourceResult result = bound.adapter().fetch(hkSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat((BigDecimal) result.getData().get("pb")).isEqualByComparingTo("3.05");
        assertThat(result.getData()).doesNotContainKey("peTtm");
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

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void forcedTencent_eastMoneyNeverCalled() {
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
        assertThat(result.getSource()).isEqualTo("腾讯估值");
        assertThat((BigDecimal) result.getData().get("peTtm")).isEqualByComparingTo("17.37");
    }

    @Test
    void forcedEastmoney_neverFallsBack() {
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
    void constructor_invalidMode_failsFast() {
        EastMoneyClient east =
                new EastMoneyClient(
                        RestClient.builder(),
                        EAST_URL,
                        "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171",
                        "f57,f162,f167");
        TencentQuoteClient tencent = new TencentQuoteClient(RestClient.builder(), TENCENT_URL);

        assertThatThrownBy(
                        () ->
                                new ValuationSourceAdapter(
                                        cache,
                                        fieldMapper,
                                        runner,
                                        new NoopCircuitBreaker(),
                                        east,
                                        tencent,
                                        "xxx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter.valuation-source");
    }

    // ---- helpers ----

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

    private record Bound(
            ValuationSourceAdapter adapter,
            MockRestServiceServer east,
            MockRestServiceServer tencent) {}

    private Bound bind(
            String mode,
            Consumer<MockRestServiceServer> eastExpect,
            Consumer<MockRestServiceServer> tencentExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder tencentBuilder = RestClient.builder();
        MockRestServiceServer tencentServer = MockRestServiceServer.bindTo(tencentBuilder).build();
        tencentExpect.accept(tencentServer);
        EastMoneyClient east =
                new EastMoneyClient(
                        eastBuilder,
                        EAST_URL,
                        "f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171",
                        "f57,f162,f167");
        TencentQuoteClient tencent = new TencentQuoteClient(tencentBuilder, TENCENT_URL);
        ValuationSourceAdapter adapter =
                new ValuationSourceAdapter(
                        cache, fieldMapper, runner, new NoopCircuitBreaker(), east, tencent, mode);
        return new Bound(adapter, eastServer, tencentServer);
    }

    private Bound bind(
            Consumer<MockRestServiceServer> eastExpect,
            Consumer<MockRestServiceServer> tencentExpect) {
        return bind("auto", eastExpect, tencentExpect);
    }
}
