package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 巨潮备选降级 × 聚合编排集成测试（ADR-0034 T58 故障注入演练·公告侧，对齐新浪财务侧 {@link SinaFallbackAggregationIntegrationTest}
 * 先例）：东财 {@code np-anotice-stock} HTTP 失败（ISSUE-A 同日 WAF 收紧形态）时，真实 AnnounceSourceAdapter 经巨潮兜底恢复
 * OK，聚合页 announce 分区拿到数据；兜底轮 {@code RawFetch.source} 标注「东方财富公告→巨潮资讯备选」。
 *
 * <p>分层说明：本测试在 infrastructure 包内组装应用层 {@link AggregationService}（infrastructure → application
 * 为既有依赖方向），SourceRepository 用 Mockito 替身、无 DB/无真实外呼。
 */
class CninfoFallbackAggregationIntegrationTest {

    private static final String EAST_URL = "https://np-anotice.test/api/security/ann";
    private static final String EAST_REFERER = "https://data.eastmoney.com/";
    private static final String EAST_DETAIL_TEMPLATE =
            "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf";
    private static final String CNINFO_QUERY_URL = "https://cninfo.test/new/hisAnnouncement/query";
    private static final String CNINFO_STOCK_LIST_URL =
            "https://cninfo.test/new/data/szse_stock.json";
    private static final String CNINFO_DETAIL_PREFIX = "https://static.cninfo.test/";
    private static final Duration ORG_ID_TTL = Duration.ofHours(24);

    private static final String CNINFO_STOCK_LIST_JSON =
            """
            [{"orgId":"gssh0600519","category":"A股","code":"600519","pinyin":"GZMT","zwjc":"贵州茅台"}]
            """;

    /** 600519 实测口径：announcementTime 1786723200000 ms = 2026-08-15 00:00 Asia/Shanghai。 */
    private static final String CNINFO_QUERY_JSON =
            """
            {"announcements":[
              {"announcementId":"1225475868","announcementTitle":"贵州茅台:2026年半年度报告",
               "announcementTime":1786723200000,"secCode":"600519","secName":"贵州茅台",
               "adjunctUrl":"finalpage/2026-08-15/1225475868.PDF"}],
             "totalRecordNum":486,"hasMore":true,"pageNum":1}
            """;

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
    void detailPage_announcePartitionRecoversViaCninfoWhenEastMoneyFails() {
        SubjectRepository repository = Mockito.mock(SubjectRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(subject()));
        // 故障注入：东财公告端点 500（ISSUE-A WAF 拒绝形态）；巨潮兜底（orgId 表 + 查询 POST）
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "np-anotice.test")))
                                        .andRespond(withServerError()),
                        server -> {
                            server.expect(requestTo(CNINFO_STOCK_LIST_URL))
                                    .andRespond(
                                            withSuccess(
                                                    CNINFO_STOCK_LIST_JSON,
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(CNINFO_QUERY_URL))
                                    .andRespond(
                                            withSuccess(
                                                    CNINFO_QUERY_JSON, MediaType.APPLICATION_JSON));
                        });
        // 同一 adapter 复用（fetch 内建缓存）：详情两次调用，第二次走缓存（兜底轮单次外呼由 mock 单期待 + verify 证）
        AggregationService service =
                new AggregationService(
                        repository, List.of(bound.adapter()), Runnable::run, () -> 2000L);

        SubjectDetail first = service.getDetail(1L, Set.of());
        SubjectDetail second = service.getDetail(1L, Set.of());

        assertThat(first.sourceStatus()).containsEntry("announce", "ok");
        assertThat(first.announcements()).hasSize(1);
        Map<String, Object> item = first.announcements().get(0);
        assertThat(item.get("externalId")).isEqualTo("1225475868");
        assertThat(item.get("title")).isEqualTo("贵州茅台:2026年半年度报告");
        assertThat(item.get("publishedAt")).isEqualTo("2026-08-15T00:00:00");
        assertThat(item.get("url"))
                .isEqualTo("https://static.cninfo.test/finalpage/2026-08-15/1225475868.PDF");
        assertThat(item).doesNotContainKey("category");
        // 第二次详情：缓存命中（巨潮兜底产物入既有 300s TTL，不放大备源限速压力）
        assertThat(second.sourceStatus()).containsEntry("announce", "ok");
        assertThat(second.announcements()).hasSize(1);
        bound.east().verify();
        bound.cninfo().verify();
    }

    @Test
    void fallbackRound_rawFetchSourceLabeledCninfoBackup() {
        // 可观测验收（方案 §1）：兜底轮 RawFetch.source 标注「东方财富公告→巨潮资讯备选」——健康徽章/连通性测试可区分
        Bound bound =
                bind(
                        server ->
                                server.expect(
                                                requestTo(
                                                        org.hamcrest.Matchers.containsString(
                                                                "np-anotice.test")))
                                        .andRespond(withServerError()),
                        server -> {
                            server.expect(requestTo(CNINFO_STOCK_LIST_URL))
                                    .andRespond(
                                            withSuccess(
                                                    CNINFO_STOCK_LIST_JSON,
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(CNINFO_QUERY_URL))
                                    .andRespond(
                                            withSuccess(
                                                    CNINFO_QUERY_JSON, MediaType.APPLICATION_JSON));
                        });

        SourceResult result = bound.adapter().fetchFresh(subject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告→巨潮资讯备选");
        bound.east().verify();
        bound.cninfo().verify();
    }

    // ---- helpers ----

    private record Bound(
            AnnounceSourceAdapter adapter,
            MockRestServiceServer east,
            MockRestServiceServer cninfo) {}

    private Bound bind(
            java.util.function.Consumer<MockRestServiceServer> eastExpect,
            java.util.function.Consumer<MockRestServiceServer> cninfoExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder cninfoBuilder = RestClient.builder();
        MockRestServiceServer cninfoServer = MockRestServiceServer.bindTo(cninfoBuilder).build();
        cninfoExpect.accept(cninfoServer);
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        new SourceCache(),
                        new FieldMapper(new ObjectMapper()),
                        new ResilienceRunner(exec),
                        new NoopCircuitBreaker(),
                        new EastMoneyAnnounceClient(
                                eastBuilder, EAST_URL, 3, EAST_DETAIL_TEMPLATE, EAST_REFERER),
                        new CninfoAnnounceClient(
                                cninfoBuilder,
                                CNINFO_QUERY_URL,
                                CNINFO_STOCK_LIST_URL,
                                CNINFO_DETAIL_PREFIX,
                                ORG_ID_TTL));
        return new Bound(adapter, eastServer, cninfoServer);
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
