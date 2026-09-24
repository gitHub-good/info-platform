package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 新浪备选降级 × 聚合编排集成测试（ADR-0034 T56，对齐 ADR-0031 腾讯先例）：东财 datacenter 9501 空体事故（200 + {@code
 * result:null} 错误体）时，真实 FinanceSourceAdapter 经新浪两页兜底恢复 OK，聚合页 finance 分区拿到数据（对齐任务验收「财务分区恢复」）。
 *
 * <p>分层说明：本测试在 infrastructure 包内组装应用层 {@link AggregationService}（infrastructure → application
 * 为既有依赖方向，见 SubjectListSource 端口先例），SourceRepository 用 Mockito 替身、无 DB/无真实外呼。
 */
class SinaFallbackAggregationIntegrationTest {

    private static final String EAST_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final String EAST_REFERER = "https://data.eastmoney.com/";
    private static final String PROFIT_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_ProfitStatement/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";
    private static final String GUIDE_TEMPLATE =
            "https://sina.test/corp/go.php/vFD_FinancialGuideLine/stockid/{code}/ctrl/{year}"
                    + "/displaytype/4.phtml";

    private ExecutorService exec;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void detailPage_financePartitionRecoversViaSinaWhenEastMoneyEmpty() {
        SubjectRepository repository = Mockito.mock(SubjectRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(subject()));
        // 东财 9501 事故签名：200 + result:null 错误体；新浪两页兜底回实测口径数据（附录 A）
        FinanceSourceAdapter financeAdapter =
                financeAdapter(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "reportName=")))
                                        .andRespond(
                                                withSuccess(
                                                        "{\"result\":null,\"data\":null,"
                                                                + "\"success\":false,\"code\":9501}",
                                                        MediaType.APPLICATION_JSON)),
                        this::expectSinaPages);
        // 同一 adapter 复用（fetch 内建缓存）：详情两次调用，第二次走缓存（新浪两页单次外呼由 mock 单期待 + verify 证）
        AggregationService service =
                new AggregationService(
                        repository, List.of(financeAdapter), Runnable::run, () -> 2000L);

        SubjectDetail first = service.getDetail(1L, Set.of());
        SubjectDetail second = service.getDetail(1L, Set.of());

        assertThat(first.sourceStatus()).containsEntry("finance", "ok");
        // 新浪万元 ×10000 → 东财键 → eastmoney-finance.json 映射（配置零改动）
        assertThat((BigDecimal) first.finance().get("revenue")).isEqualByComparingTo("92278072100");
        assertThat((BigDecimal) first.finance().get("netProfit"))
                .isEqualByComparingTo("44516880400");
        assertThat((BigDecimal) first.finance().get("roe")).isEqualByComparingTo("16.75");
        assertThat(first.finance().get("reportDate")).isEqualTo("2026-06-30T00:00:00");
        assertThat(second.sourceStatus()).containsEntry("finance", "ok");
        assertThat((BigDecimal) second.finance().get("revenue"))
                .isEqualByComparingTo("92278072100");
    }

    // ---- helpers ----

    private FinanceSourceAdapter financeAdapter(
            Consumer<MockRestServiceServer> eastExpect,
            Consumer<MockRestServiceServer> sinaExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder sinaBuilder = RestClient.builder();
        MockRestServiceServer sinaServer = MockRestServiceServer.bindTo(sinaBuilder).build();
        sinaExpect.accept(sinaServer);
        return new FinanceSourceAdapter(
                new SourceCache(),
                new FieldMapper(new ObjectMapper()),
                new ResilienceRunner(exec),
                new NoopCircuitBreaker(),
                new EastMoneyFinanceClient(eastBuilder, EAST_URL, EAST_REFERER),
                new SinaFinanceClient(sinaBuilder, PROFIT_TEMPLATE, GUIDE_TEMPLATE));
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

    private static Subject subject() {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("eastmoney", "1.600519"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
