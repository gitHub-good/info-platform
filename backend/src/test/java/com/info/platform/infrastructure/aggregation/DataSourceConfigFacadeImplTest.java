package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.aggregation.DataSourceConfigFacade.AggregationGlobalUpdate;
import com.info.platform.application.aggregation.DataSourceConfigFacade.ConnectivityResult;
import com.info.platform.application.aggregation.DataSourceConfigValidator;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DataSourceConfigFacadeImpl} 单测（T36，方案 §4.4.2 + §6 测试要点）。
 *
 * <p>真实链路：{@link RuntimeConfigService}（内存仓储 + 真校验器）+ 真实 {@link ConfigCenter} 快照 + 真实
 * RoutingSourceAdapter（包 Fake adapter）；事件/标的仓储 Mockito mock。覆盖：GET 视图形状（7 源 + 健康空态 + effectiveModes
 * 全 LIVE）、PATCH 合并与校验 2001、 30065 防呆、聚合总超时条热改、连通性测试（REAL 试拉 / MOCK 本地校验 + note / 无标的 404 语义）。
 */
class DataSourceConfigFacadeImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T01:00:00Z");

    private static class InMemoryRepository implements RuntimeConfigRepository {
        final Map<String, RuntimeConfig> rows = new LinkedHashMap<>();

        @Override
        public Optional<RuntimeConfig> findByKey(String configKey) {
            return Optional.ofNullable(rows.get(configKey));
        }

        @Override
        public List<RuntimeConfig> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public RuntimeConfig save(RuntimeConfig config) {
            rows.put(config.getConfigKey(), config);
            return config;
        }
    }

    /** 可控行为的 Fake adapter（连通性测试走 AbstractSourceAdapter#fetchFresh）。 */
    static final class FakeAdapter extends AbstractSourceAdapter {
        private final SourceCode code;
        private final java.util.function.Function<Subject, Optional<RawFetch>> fetchFn;

        FakeAdapter(
                SourceCode code,
                ResilienceRunner runner,
                java.util.function.Function<Subject, Optional<RawFetch>> fetchFn) {
            super(
                    new SourceCache(),
                    new FieldMapper(new ObjectMapper()),
                    runner,
                    new NoopCircuitBreaker());
            this.code = code;
            this.fetchFn = fetchFn;
        }

        @Override
        public SourceCode sourceCode() {
            return code;
        }

        @Override
        protected String sourceLabel() {
            return "fake-" + code.name().toLowerCase();
        }

        @Override
        protected List<FieldMapping> mappingConfig() {
            return List.of(new FieldMapping("items", "items", Transform.NONE));
        }

        @Override
        protected ResilienceSpec resilienceSpec() {
            return ResilienceSpec.noRetry(Duration.ofSeconds(2));
        }

        @Override
        protected Optional<RawFetch> doFetch(Subject subject) {
            return fetchFn.apply(subject);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryRepository repository = new InMemoryRepository();
    private final DataSourceEventRepository eventRepository = mock(DataSourceEventRepository.class);
    private final SubjectRepository subjectRepository = mock(SubjectRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    private RuntimeConfigService configService;
    private ConfigCenter configCenter;
    private DataSourceConfigFacade facade;
    private FakeAdapter realQuote;
    private FakeAdapter mockQuote;

    @BeforeEach
    void setUp() {
        configService =
                new RuntimeConfigService(
                        repository,
                        List.of(
                                new DataSourceConfigValidator(),
                                new com.info.platform.application.common
                                        .AggregationGlobalConfigValidator()),
                        event -> {},
                        clock,
                        objectMapper);
        configCenter = new ConfigCenter(configService, List.of(), null, objectMapper);
        ResilienceRunner runner = new ResilienceRunner(executor);
        realQuote =
                new FakeAdapter(
                        SourceCode.QUOTE,
                        runner,
                        s ->
                                Optional.of(
                                        new RawFetch(
                                                Map.of("items", List.of(Map.of("price", 1))),
                                                "real-quote",
                                                NOW)));
        mockQuote =
                new FakeAdapter(
                        SourceCode.QUOTE,
                        runner,
                        s ->
                                Optional.of(
                                        new RawFetch(
                                                Map.of("items", List.of(Map.of("price", 2))),
                                                "mock-quote",
                                                NOW)));
        List<RoutingSourceAdapter> routings =
                List.of(
                        new RoutingSourceAdapter(
                                SourceCode.QUOTE, realQuote, mockQuote, configCenter));
        facade =
                new DataSourceConfigFacadeImpl(
                        configService,
                        configCenter,
                        eventRepository,
                        subjectRepository,
                        routings,
                        clock,
                        objectMapper);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private void store(String key, String json) {
        repository.save(RuntimeConfig.create(key, json, null, NOW));
        configService.reload();
    }

    @Test
    void view_sevenSources_healthEmptyState_effectiveModesAllLive() {
        when(eventRepository.findLatestBySourceCode(SourceCode.QUOTE)).thenReturn(Optional.empty());
        when(eventRepository.countErrorsSince(SourceCode.QUOTE, NOW.minus(Duration.ofHours(24))))
                .thenReturn(0L);

        DataSourceConfigFacade.DataSourceConfigView view = facade.view();

        assertThat(view.sources()).hasSize(7);
        var quote = view.sources().get(0);
        assertThat(quote.sourceCode()).isEqualTo("QUOTE");
        assertThat(quote.label()).isEqualTo("行情源");
        // 键缺失 → 代码缺省视图（mode=MOCK、超时 1500）
        assertThat(quote.mode()).isEqualTo("MOCK");
        assertThat(quote.timeoutMillis()).isEqualTo(1500);
        assertThat(quote.retries()).isZero();
        assertThat(quote.cacheTtlSeconds()).isEqualTo(5);
        assertThat(quote.updatedAt()).isNull();
        assertThat(quote.effectiveModes()).containsEntry("mode", "LIVE");
        // 健康空态（PRD 场景 3.5）：从未有事件 → lastEventType/lastEventAt null，不报错
        assertThat(quote.health().lastEventType()).isNull();
        assertThat(quote.health().lastEventAt()).isNull();
        assertThat(quote.health().errors24h()).isZero();
        // 聚合总超时条
        assertThat(view.aggregation().detailTimeoutMillis()).isEqualTo(2000);
        assertThat(view.aggregation().effectiveModes())
                .containsEntry("detailTimeoutMillis", "LIVE");
    }

    @Test
    void view_healthFromLatestEventIncludingOkHeartbeat() {
        store("datasource.QUOTE", quoteDoc("REAL"));
        DataSourceEvent ok =
                DataSourceEvent.reconstruct(
                        9L,
                        SourceCode.QUOTE,
                        DataSourceEventType.OK,
                        1L,
                        "ok",
                        NOW.minus(Duration.ofMinutes(5)),
                        NOW.minus(Duration.ofMinutes(5)));
        when(eventRepository.findLatestBySourceCode(SourceCode.QUOTE)).thenReturn(Optional.of(ok));
        when(eventRepository.countErrorsSince(SourceCode.QUOTE, NOW.minus(Duration.ofHours(24))))
                .thenReturn(2L);

        var card = facade.view().sources().get(0);

        assertThat(card.health().lastEventType()).isEqualTo("OK");
        assertThat(card.health().lastEventAt()).isEqualTo("2026-09-22T00:55:00Z");
        assertThat(card.health().errors24h()).isEqualTo(2);
        assertThat(card.updatedAt()).isEqualTo(NOW.toString());
    }

    @Test
    void update_mergesFields_hotEffectVisibleInSnapshot() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        var saved =
                facade.update(
                        SourceCode.QUOTE,
                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                null, "MOCK", 3000L, 1, 10L, null, null, null, NOW.toString()));

        // 保存即换快照：路由读到的 mode/超时已是新值（下一次取数生效）
        assertThat(saved.mode()).isEqualTo("MOCK");
        assertThat(saved.timeoutMillis()).isEqualTo(3000);
        assertThat(saved.retries()).isEqualTo(1);
        assertThat(saved.cacheTtlSeconds()).isEqualTo(10);
        assertThat(configCenter.dataSource(SourceCode.QUOTE).mode())
                .isEqualTo(com.info.platform.infrastructure.common.RuntimeDataSource.Mode.MOCK);
        assertThat(configCenter.dataSource(SourceCode.QUOTE).timeoutMillis()).isEqualTo(3000);
    }

    @Test
    void update_paramsReplacedWholly_validationRejectsBadUrl() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        SourceCode.QUOTE,
                                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Map.of("quoteUrl", "not-a-url"),
                                                null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("quoteUrl");
        // 校验失败原值不变
        assertThat(configCenter.dataSource(SourceCode.QUOTE).paramString("quoteUrl", "x"))
                .isEqualTo("https://push2.eastmoney.com/api/qt/stock/get");
    }

    // ---- ADR-0033 降级链字段（视图折算 + 写路径统一 + 旧键淘汰） ----

    @Test
    void view_chainAbsent_foldsLegacyBackupSource_andExposesProviders() {
        // 存量行形状：无 fallbackChain、params.backupSource=tencent → 视图折算单元素链（旧强制单源语义）
        store(
                "datasource.QUOTE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"backupSource\":\"tencent\"}}");

        var card = facade.view().sources().get(0);

        assertThat(card.fallbackChain()).containsExactly("tencent");
        assertThat(card.availableProviders()).containsExactly("eastmoney", "tencent");
        assertThat(card.effectiveModes()).containsEntry("fallbackChain", "LIVE");
    }

    @Test
    void view_chainAbsentLegacyAuto_foldsToFullDefaultChain() {
        store(
                "datasource.QUOTE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"backupSource\":\"auto\"}}");

        assertThat(facade.view().sources().get(0).fallbackChain())
                .containsExactly("eastmoney", "tencent");
    }

    @Test
    void view_singleProviderSource_chainIsOnlyProvider() {
        // 单 provider 源：链退化为唯一 provider（页面呈现「暂无备选源」）
        store(
                "datasource.FINANCE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":3600,\"params\":{}}");

        var card = facade.view().sources().get(1);

        assertThat(card.availableProviders()).containsExactly("eastmoney");
        assertThat(card.fallbackChain()).containsExactly("eastmoney");
    }

    @Test
    void update_fallbackChainWritten_legacyBackupSourceSunset() {
        store(
                "datasource.QUOTE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"backupSource\":\"auto\"}}");

        var saved =
                facade.update(
                        SourceCode.QUOTE,
                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of("tencent", "eastmoney"),
                                null,
                                NOW.toString()));

        // 写路径统一 fallbackChain：视图即新链（下一次取数生效），旧 backupSource 键被顺带淘汰
        assertThat(saved.fallbackChain()).containsExactly("tencent", "eastmoney");
        assertThat(configCenter.dataSource(SourceCode.QUOTE).fallbackChain())
                .containsExactly("tencent", "eastmoney");
        assertThat(configCenter.dataSource(SourceCode.QUOTE).paramString("backupSource", null))
                .isNull();
    }

    @Test
    void update_emptyChainMeansPrimaryOnly_andIsPersisted() {
        store(
                "datasource.QUOTE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}");

        var saved =
                facade.update(
                        SourceCode.QUOTE,
                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                null, null, null, null, null, null, List.of(), null, null));

        // 页面清空备选（仅主源）：空链落库，视图折算为默认主源单元素链
        assertThat(saved.fallbackChain()).containsExactly("eastmoney");
        assertThat(configCenter.dataSource(SourceCode.QUOTE).fallbackChain()).isEmpty();
    }

    @Test
    void update_invalidChainMember_rejectedByValidator() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        SourceCode.QUOTE,
                                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("eastmoney", "sina"),
                                                null,
                                                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在源 QUOTE 可用 provider");
        // 校验失败原值不变
        assertThat(configCenter.dataSource(SourceCode.QUOTE).fallbackChain()).isNull();
    }

    // ---- P1-5b 失败负缓存 TTL 字段（体检「FAILED/MISSING 不负缓存」配置面） ----

    @Test
    void update_legacyDocWithoutFailureTtl_backfillsDefaultAndVisible() {
        // 存量 runtime_config 行无 failureCacheTtlSeconds（本批禁迁移）：保存补种代码缺省（QUOTE=10s），卡片视图可见
        store("datasource.QUOTE", quoteDoc("REAL"));

        var saved =
                facade.update(
                        SourceCode.QUOTE,
                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                null, null, null, null, null, null, null, null, null));

        assertThat(saved.failureCacheTtlSeconds()).isEqualTo(10);
        assertThat(configCenter.dataSource(SourceCode.QUOTE).failureCacheTtl().toSeconds())
                .isEqualTo(10);
    }

    @Test
    void update_failureTtlProvided_savedAndHotEffective() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        var saved =
                facade.update(
                        SourceCode.QUOTE,
                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                null, null, null, null, null, 45L, null, null, NOW.toString()));

        assertThat(saved.failureCacheTtlSeconds()).isEqualTo(45);
        assertThat(configCenter.dataSource(SourceCode.QUOTE).failureCacheTtl().toSeconds())
                .isEqualTo(45);
    }

    @Test
    void update_failureTtlNotPositive_validationRejects() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        SourceCode.QUOTE,
                                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                                null, null, null, null, null, 0L, null, null,
                                                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failureCacheTtlSeconds");
    }

    @Test
    void update_expectedUpdatedAtMismatch_throws30065() {
        store("datasource.QUOTE", quoteDoc("REAL"));

        assertThatThrownBy(
                        () ->
                                facade.update(
                                        SourceCode.QUOTE,
                                        new DataSourceConfigFacade.DataSourceConfigUpdate(
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                NOW.minus(Duration.ofHours(1)).toString())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFIG_CONFLICT));
    }

    @Test
    void updateAggregation_changesTimeoutVisibleImmediately() {
        store("aggregation.global", "{\"detailTimeoutMillis\":2000}");

        var saved = facade.updateAggregation(new AggregationGlobalUpdate(3000L, NOW.toString()));

        assertThat(saved.detailTimeoutMillis()).isEqualTo(3000);
        assertThat(
                        configCenter
                                .document("aggregation.global")
                                .orElseThrow()
                                .path("detailTimeoutMillis")
                                .asLong())
                .isEqualTo(3000);
    }

    @Test
    void connectivityTest_realMode_freshFetchOnceWithLatencyAndCount() {
        store("datasource.QUOTE", quoteDoc("REAL"));
        when(subjectRepository.findFirstActive()).thenReturn(Optional.of(subject()));

        ConnectivityResult result = facade.connectivityTest(SourceCode.QUOTE);

        assertThat(result.ok()).isTrue();
        assertThat(result.mode()).isEqualTo("REAL");
        assertThat(result.latencyMillis()).isNotNull();
        assertThat(result.itemCount()).isEqualTo(1);
        assertThat(result.note()).isNull();
    }

    @Test
    void connectivityTest_mockMode_localCheckWithNote() {
        store("datasource.QUOTE", quoteDoc("MOCK"));
        when(subjectRepository.findFirstActive()).thenReturn(Optional.of(subject()));

        ConnectivityResult result = facade.connectivityTest(SourceCode.QUOTE);

        assertThat(result.ok()).isTrue();
        assertThat(result.note()).contains("mock 模式").contains("未发起真实外呼");
    }

    @Test
    void connectivityTest_noSubject_returnsNotOkWithReason() {
        when(subjectRepository.findFirstActive()).thenReturn(Optional.empty());

        ConnectivityResult result = facade.connectivityTest(SourceCode.QUOTE);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("无启用标的");
    }

    private static String quoteDoc(String mode) {
        return "{\"enabled\":true,\"mode\":\""
                + mode
                + "\",\"timeoutMillis\":1500,\"retries\":0,\"cacheTtlSeconds\":5,"
                + "\"params\":{\"quoteUrl\":\"https://push2.eastmoney.com/api/qt/stock/get\","
                + "\"fields\":\"f43,f57\"}}";
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
