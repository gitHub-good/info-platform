package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
import com.info.platform.domain.aggregation.SourceProviders;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * FinanceSourceAdapter 新浪备选降级单测（ADR-0034 T56，修前红回归用例）：东财 datacenter 9501 事故（空响应错误体）期间财务分区裸奔——
 * 空响应/HTTP 错误自动降级新浪两页（vFD_ProfitStatement + vFD_FinancialGuideLine），营收/归母净利/ROE 等经东财键中间结构映射（ {@code
 * eastmoney-finance.json} 零改动）。
 *
 * <p>覆盖：auto 东财成功不碰新浪 / 东财空响应（9501 错误体形态）降级新浪 OK（字段映射 + 来源标注）/ 双失败 MISSING / 强制 ["sina"] 单源 / 空链仅主源
 * / 链热切换 / 注册表默认链 [eastmoney, sina] / 缺代码不发请求。
 */
class FinanceSourceAdapterFallbackTest {

    private static final String EAST_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String EAST_REFERER = "https://data.eastmoney.com/";
    private static final String PROFIT_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_ProfitStatement/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";
    private static final String GUIDE_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_FinancialGuideLine/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";

    /** 东财 9501 事故签名（f3cdcc1 修复对象的当日形态）：200 + result:null 错误体——须触发降级而非当无数据裸奔。 */
    private static final String EAST_9501_BODY =
            "{\"result\":null,\"data\":null,\"success\":false,\"message\":\"fail\",\"code\":9501}";

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
    void registry_defaultChain_eastmoneyThenSina() {
        // 注册表补行即默认链（auto 语义，存量 DB 行无 fallbackChain 时自动生效）——ADR-0034 §3
        assertThat(SourceProviders.providers(SourceCode.FINANCE))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.SINA);
        FinanceSourceAdapter adapter =
                new FinanceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyFinanceClient(RestClient.builder(), EAST_URL, EAST_REFERER),
                        new SinaFinanceClient(
                                RestClient.builder(), PROFIT_TEMPLATE, GUIDE_TEMPLATE));
        assertThat(adapter.currentChain())
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.SINA);
    }

    @Test
    void auto_eastMoneyOk_sinaNeverCalled() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("reportName=")))
                                        .andRespond(
                                                withSuccess(
                                                        eastOkBody(), MediaType.APPLICATION_JSON)),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.sina().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富财务");
        assertThat((BigDecimal) result.getData().get("roe")).isEqualByComparingTo("16.75");
    }

    @Test
    void auto_eastMoneyEmpty9501_fallsBackToSinaWithMappedFields() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("reportName=")))
                                        .andRespond(
                                                withSuccess(
                                                        EAST_9501_BODY,
                                                        MediaType.APPLICATION_JSON)),
                        this::expectSinaPages);

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.sina().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富财务→新浪备选");
        // 新浪两页 → 东财键中间结构 → 既有 eastmoney-finance.json 映射零改动
        assertThat((BigDecimal) result.getData().get("revenue"))
                .isEqualByComparingTo("92278072100");
        assertThat((BigDecimal) result.getData().get("netProfit"))
                .isEqualByComparingTo("44516880400");
        assertThat((BigDecimal) result.getData().get("roe")).isEqualByComparingTo("16.75");
        assertThat((BigDecimal) result.getData().get("netProfitMargin"))
                .isEqualByComparingTo("50.7516");
        // 毛利率 '--' 缺失不产出（金融股同语义，需求许可）
        assertThat(result.getData()).doesNotContainKey("grossProfitMargin");
        assertThat(result.getData().get("reportDate")).isEqualTo("2026-06-30T00:00:00");
        assertThat(result.getData().get("externalCode")).isEqualTo("600519");
    }

    @Test
    void auto_bothHttpError_degradesToMissing() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("reportName=")))
                                        .andRespond(withServerError()),
                        server ->
                                server.expect(requestTo(containsString("vFD_ProfitStatement")))
                                        .andRespond(withServerError()));

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void forcedSinaSingleSource_eastMoneyNeverCalled() {
        Bound bound =
                bind(new AtomicReference<>(List.of("sina")), server -> {}, this::expectSinaPages);

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        // 强制单源链位 0 标注：链首 provider + 业务名（排障入口，页面可编）
        assertThat(result.getSource()).isEqualTo("新浪财务");
        assertThat((BigDecimal) result.getData().get("revenue"))
                .isEqualByComparingTo("92278072100");
    }

    @Test
    void emptyChain_primaryOnly_noFallbackAttempt() {
        Bound bound =
                bind(
                        new AtomicReference<>(List.of()),
                        server ->
                                server.expect(requestTo(containsString("reportName=")))
                                        .andRespond(withServerError()),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.sina().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    @Test
    void chainHotSwitch_takesEffectOnNextFetch() {
        AtomicReference<List<String>> chain = new AtomicReference<>(List.of("eastmoney"));
        Bound bound =
                bind(
                        chain,
                        server -> {
                            // 两次取数均先走东财（auto 链首恒东财，恢复回归靠每轮先试主源）——各期待一次 9501 空体
                            for (int i = 0; i < 2; i++) {
                                server.expect(requestTo(containsString("reportName=")))
                                        .andRespond(
                                                withSuccess(
                                                        EAST_9501_BODY,
                                                        MediaType.APPLICATION_JSON));
                            }
                        },
                        this::expectSinaPages);

        // 首轮：链 [eastmoney] 主源空响应 → 无备选 → MISSING（fetchFresh 绕缓存）
        assertThat(bound.adapter().fetchFresh(aShareSubject()).getStatus())
                .isEqualTo(SourceStatus.MISSING);

        // 页面保存等价动作：链补上新浪备选 → 下一次取数即新链（热生效，无重启）
        chain.set(List.of("eastmoney", "sina"));

        SourceResult second = bound.adapter().fetchFresh(aShareSubject());
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getSource()).isEqualTo("东方财富财务→新浪备选");
    }

    @Test
    void missingCodes_returnsMissingWithoutHttpCall() {
        // 既有语义保留：两路径代码均缺 → MISSING 不发任何 HTTP（链消费点改造后不回退该防御）
        Bound bound = bind(null, server -> {}, server -> {});

        SourceResult result =
                bound.adapter()
                        .fetch(
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
                                        Instant.parse("2026-09-20T00:00:00Z")));

        bound.east().verify();
        bound.sina().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    // ---- helpers ----

    private static String eastOkBody() {
        return """
                {"result":{"pages":103,"data":[{
                  "SECURITY_CODE":"600519","REPORT_DATE":"2026-06-30 00:00:00",
                  "TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,
                  "XSJLL":50.75,"XSMLL":89.56,"ROEJQ":16.75}]}}
                """;
    }

    /** 新浪两页期望（当年 ctrl：利润表 + 指标页，GBK）。 */
    private void expectSinaPages(MockRestServiceServer server) {
        int year = Year.now().getValue();
        server.expect(
                        requestTo(
                                "https://sina.test/corp/go.php/vFD_ProfitStatement/stockid/600519"
                                        + "/ctrl/"
                                        + year
                                        + "/displaytype/4.phtml"))
                .andRespond(
                        withSuccess(
                                SinaFinanceFixtures.PROFIT_600519.getBytes(SinaFinanceFixtures.GBK),
                                MediaType.TEXT_HTML));
        server.expect(
                        requestTo(
                                "https://sina.test/corp/go.php/vFD_FinancialGuideLine/stockid/600519"
                                        + "/ctrl/"
                                        + year
                                        + "/displaytype/4.phtml"))
                .andRespond(
                        withSuccess(
                                SinaFinanceFixtures.GUIDE_600519.getBytes(SinaFinanceFixtures.GBK),
                                MediaType.TEXT_HTML));
    }

    /** 财务源配置中心 stub：dataSource(FINANCE) 按当前链值现算视图（换值 = 换快照，等价页面保存）。 */
    private static ConfigCenter financeCenter(AtomicReference<List<String>> chain) {
        ConfigCenter center = Mockito.mock(ConfigCenter.class);
        when(center.dataSource(SourceCode.FINANCE))
                .thenAnswer(
                        inv ->
                                new RuntimeDataSource(
                                        SourceCode.FINANCE,
                                        true,
                                        RuntimeDataSource.Mode.REAL,
                                        2000,
                                        0,
                                        3600,
                                        30,
                                        Map.of(),
                                        chain.get()));
        return center;
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

    private record Bound(
            FinanceSourceAdapter adapter, MockRestServiceServer east, MockRestServiceServer sina) {}

    /** 绑定双源 mock 服务端；chain 为 null 时不接配置中心（auto 默认链 = 注册表全链）。 */
    private Bound bind(
            AtomicReference<List<String>> chain,
            java.util.function.Consumer<MockRestServiceServer> eastExpect,
            java.util.function.Consumer<MockRestServiceServer> sinaExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder sinaBuilder = RestClient.builder();
        MockRestServiceServer sinaServer = MockRestServiceServer.bindTo(sinaBuilder).build();
        sinaExpect.accept(sinaServer);
        FinanceSourceAdapter adapter =
                new FinanceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyFinanceClient(eastBuilder, EAST_URL, EAST_REFERER),
                        new SinaFinanceClient(sinaBuilder, PROFIT_TEMPLATE, GUIDE_TEMPLATE));
        if (chain != null) {
            adapter.configCenter = financeCenter(chain);
        }
        return new Bound(adapter, eastServer, sinaServer);
    }
}
