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
 * 腾讯备选降级 × 聚合编排集成测试（ADR-0031）：东财 push2 封禁（200 + 空 data）时，真实 QuoteSourceAdapter 经腾讯兜底恢复 OK，聚合页 quote
 * 分区拿到数据（对齐任务验收「聚合页 quote 分区恢复数据」）。
 *
 * <p>分层说明：本测试在 infrastructure 包内组装应用层 {@link AggregationService}（infrastructure → application
 * 为既有依赖方向， 见 SubjectListSource 端口先例），SourceRepository 用 Mockito 替身、无 DB/无真实外呼。
 */
class TencentFallbackAggregationIntegrationTest {

    private static final String EAST_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=";

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
    void detailPage_quotePartitionRecoversViaTencentWhenEastMoneyBanned() {
        SubjectRepository repository = Mockito.mock(SubjectRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(subject()));
        // 东财封禁签名：200 + data:null；腾讯兜底回真实行
        QuoteSourceAdapter quoteAdapter =
                quoteAdapter(
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
        // 同一 adapter 复用（fetch 内建缓存）：详情两次调用，第二次走缓存（腾讯单次外呼由 mock 单期待 + verify 证）
        AggregationService service =
                new AggregationService(
                        repository, List.of(quoteAdapter), Runnable::run, () -> 2000L);

        SubjectDetail first = service.getDetail(1L, Set.of());
        SubjectDetail second = service.getDetail(1L, Set.of());

        assertThat(first.sourceStatus()).containsEntry("quote", "ok");
        assertThat((BigDecimal) first.quote().get("price")).isEqualByComparingTo("1237.00");
        assertThat((BigDecimal) first.quote().get("changePct")).isEqualByComparingTo("-1.14");
        assertThat(first.quote().get("name")).isEqualTo("贵州茅台");
        assertThat(second.sourceStatus()).containsEntry("quote", "ok");
        assertThat((BigDecimal) second.quote().get("price")).isEqualByComparingTo("1237.00");
    }

    // ---- helpers ----

    private QuoteSourceAdapter quoteAdapter(
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
        return new QuoteSourceAdapter(
                new SourceCache(),
                new FieldMapper(new ObjectMapper()),
                new ResilienceRunner(exec),
                new NoopCircuitBreaker(),
                east,
                tencent,
                "auto");
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
