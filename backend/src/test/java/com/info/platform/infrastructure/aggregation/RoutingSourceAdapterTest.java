package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RoutingSourceAdapter} 热路由单测（T36 / ADR-0017 冲突解法 1，方案 §6「切 mock → 路由立即走 mock 分区」）。
 *
 * <p>真实链路：{@link RuntimeConfigService}（内存仓储）+ 真实 {@link ConfigCenter} 快照，real/mock adapter 用
 * Mockito 替身。覆盖：MOCK/REAL 分发、enabled=false 短路 MISSING 不外调、<b>运行时改 mode 下一次 fetch 即走新
 * adapter（热切换）</b>、 supportedSubjectTypes 跟随当前路由目标。
 */
class RoutingSourceAdapterTest {

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");

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

    private final InMemoryRepository repository = new InMemoryRepository();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeConfigService configService;
    private ConfigCenter configCenter;
    private SourceAdapter realAdapter;
    private SourceAdapter mockAdapter;
    private RoutingSourceAdapter routing;

    @BeforeEach
    void setUp() {
        configService =
                new RuntimeConfigService(
                        repository,
                        List.of(),
                        event -> {},
                        Clock.fixed(T1, ZoneOffset.UTC),
                        objectMapper);
        configCenter = new ConfigCenter(configService, List.of(), null, objectMapper);
        realAdapter = mock(SourceAdapter.class);
        when(realAdapter.sourceCode()).thenReturn(SourceCode.QUOTE);
        mockAdapter = mock(SourceAdapter.class);
        when(mockAdapter.sourceCode()).thenReturn(SourceCode.QUOTE);
        routing =
                new RoutingSourceAdapter(SourceCode.QUOTE, realAdapter, mockAdapter, configCenter);
    }

    private void storeMode(RuntimeDataSource.Mode mode) {
        repository.save(
                RuntimeConfig.create(
                        "datasource.QUOTE",
                        "{\"enabled\":true,\"mode\":\""
                                + mode.name()
                                + "\",\"timeoutMillis\":1500,\"retries\":0,\"cacheTtlSeconds\":5,"
                                + "\"params\":{\"quoteUrl\":\"https://q.example.com/get\",\"fields\":\"f43\"}}",
                        null,
                        T1));
        configService.reload();
    }

    // ---- M12：fetchPage 同路由口径（ADR-0037 决策 2） ----

    @Test
    void fetchPage_mockMode_delegatesToMockAdapter() {
        when(mockAdapter.fetchPage(any(Subject.class), eq(2), eq(10))).thenReturn(mockResult());
        Subject subject = subject();

        SourceResult result = routing.fetchPage(subject, 2, 10);

        verify(mockAdapter).fetchPage(subject, 2, 10);
        verify(realAdapter, never()).fetchPage(any(Subject.class), anyInt(), anyInt());
        assertThat(result.getSource()).isEqualTo("mock-quote");
    }

    @Test
    void fetchPage_realMode_delegatesToRealAdapter() {
        storeMode(RuntimeDataSource.Mode.REAL);
        when(realAdapter.fetchPage(any(Subject.class), eq(3), eq(10))).thenReturn(realOk());
        Subject subject = subject();

        SourceResult result = routing.fetchPage(subject, 3, 10);

        verify(realAdapter).fetchPage(subject, 3, 10);
        verify(mockAdapter, never()).fetchPage(any(Subject.class), anyInt(), anyInt());
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
    }

    @Test
    void fetchPage_disabled_returnsMissing_withoutCallingAnyAdapter() {
        repository.save(
                RuntimeConfig.create(
                        "datasource.QUOTE",
                        "{\"enabled\":false,\"mode\":\"REAL\",\"timeoutMillis\":1500,"
                                + "\"retries\":0,\"cacheTtlSeconds\":5,\"params\":{}}",
                        null,
                        T1));
        configService.reload();
        Subject subject = subject();

        SourceResult result = routing.fetchPage(subject, 1, 10);

        verify(realAdapter, never()).fetchPage(any(Subject.class), anyInt(), anyInt());
        verify(mockAdapter, never()).fetchPage(any(Subject.class), anyInt(), anyInt());
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    @Test
    void fetch_mockMode_delegatesToMockAdapter() {
        // Arrange：种子默认（键缺失 + 全局 mock 开关 true）→ MOCK
        when(mockAdapter.fetch(any(Subject.class))).thenReturn(mockResult());
        Subject subject = subject();

        // Act
        SourceResult result = routing.fetch(subject);

        // Assert：走 mock 分支，real 未被触碰
        verify(mockAdapter).fetch(subject);
        verify(realAdapter, never()).fetch(any(Subject.class));
        assertThat(result.getSource()).isEqualTo("mock-quote");
    }

    @Test
    void fetch_realMode_delegatesToRealAdapter() {
        // Arrange
        storeMode(RuntimeDataSource.Mode.REAL);
        when(realAdapter.fetch(any(Subject.class))).thenReturn(realOk());
        Subject subject = subject();

        // Act
        SourceResult result = routing.fetch(subject);

        // Assert
        verify(realAdapter).fetch(subject);
        verify(mockAdapter, never()).fetch(any(Subject.class));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
    }

    @Test
    void fetch_disabled_returnsMissing_withoutCallingAnyAdapter() {
        // Arrange：enabled=false → 分区 missing，不外调（方案 §4.2「enabled=false 直接返回 MISSING」）
        repository.save(
                RuntimeConfig.create(
                        "datasource.QUOTE",
                        "{\"enabled\":false,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                                + "\"cacheTtlSeconds\":5,\"params\":{}}",
                        null,
                        T1));
        configService.reload();

        // Act
        SourceResult result = routing.fetch(subject());

        // Assert
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.QUOTE);
        verify(realAdapter, never()).fetch(any(Subject.class));
        verify(mockAdapter, never()).fetch(any(Subject.class));
    }

    @Test
    void hotSwitch_modeChange_nextFetchRoutesNewAdapter() {
        // Arrange：REAL 起步
        storeMode(RuntimeDataSource.Mode.REAL);
        when(realAdapter.fetch(any(Subject.class))).thenReturn(realOk());
        when(mockAdapter.fetch(any(Subject.class))).thenReturn(mockResult());
        Subject subject = subject();

        // Act：先走 REAL，运行时切 MOCK（模拟页面保存），下一次 fetch 即走 mock
        assertThat(routing.fetch(subject).getSource()).isEqualTo("real-quote");
        storeMode(RuntimeDataSource.Mode.MOCK);
        assertThat(routing.fetch(subject).getSource()).isEqualTo("mock-quote");

        // Assert：两分支各被调一次（无重启、无换 bean）
        verify(realAdapter).fetch(subject);
        verify(mockAdapter).fetch(subject);
    }

    @Test
    void sourceCode_andRoutingIdentity() {
        assertThat(routing.sourceCode()).isEqualTo(SourceCode.QUOTE);
        assertThat(routing.realAdapter()).isSameAs(realAdapter);
        assertThat(routing.mockAdapter()).isSameAs(mockAdapter);
    }

    private SourceResult mockResult() {
        return SourceResult.ok(
                SourceCode.QUOTE, 1L, Map.of("price", "1"), "mock-quote", Instant.now());
    }

    private SourceResult realOk() {
        return SourceResult.ok(
                SourceCode.QUOTE, 1L, Map.of("price", "2"), "real-quote", Instant.now());
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
