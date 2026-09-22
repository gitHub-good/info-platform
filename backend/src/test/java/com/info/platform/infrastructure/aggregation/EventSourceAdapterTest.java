package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceAdapterInfrastructureConfig;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EventSourceAdapter 单测（T08，ADR-0013）：本地 anomaly_event 表读取 + 近期窗口过滤 + 条数截取 + items 列表映射。
 *
 * <p>覆盖三场景：主路径（近期有记录 → OK，字段逐项映射、可空字段缺省不产出）/ 边界（无记录 → MISSING、全部过期 → MISSING、窗口边界含边界值、 超上限截取 10 条）/
 * 异常（仓储抛异常 → 模板降级 MISSING，不外抛）。
 *
 * <p>AnomalyRepository 端口用手写 Fake（仅 findBySubjectId 返回构造数据，其余方法不支持）； 纯单元，不启 DB/Flyway。装配切换用 {@link
 * ApplicationContextRunner}（对齐 NewsSourceAdapterTest 模式）。
 */
class EventSourceAdapterTest {

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

    // ---- 主路径 ----

    @Test
    void fetch_recentRecords_returnsOkWithMappedItems() {
        // Arrange：两条近期记录（字段齐全 / 可空字段缺失）+ 一条过期记录
        Instant recent = Instant.now().minus(Duration.ofMinutes(10));
        FakeAnomalyRepository repository =
                new FakeAnomalyRepository(
                        List.of(
                                AnomalyRecord.reconstruct(
                                        1L,
                                        1L,
                                        AnomalyType.PRICE_CHANGE,
                                        new BigDecimal("3.25"),
                                        new BigDecimal("1680.50"),
                                        recent,
                                        "日涨跌幅 3.25% 触发阈值 3.0%",
                                        true,
                                        recent,
                                        recent),
                                AnomalyRecord.reconstruct(
                                        2L,
                                        1L,
                                        AnomalyType.EVENT,
                                        null,
                                        null,
                                        recent.plusSeconds(60),
                                        null,
                                        false,
                                        recent,
                                        recent),
                                AnomalyRecord.reconstruct(
                                        3L,
                                        1L,
                                        AnomalyType.PRICE_CHANGE,
                                        new BigDecimal("5.00"),
                                        null,
                                        // 远超 7 天窗口 → 被过滤
                                        Instant.now().minus(Duration.ofDays(30)),
                                        "过期记录",
                                        false,
                                        recent,
                                        recent)));
        EventSourceAdapter adapter = newAdapter(repository);

        // Act
        SourceResult result = adapter.fetch(subject(1L));

        // Assert：OK + 过期记录被滤掉 + 字段逐项映射 + 可空字段（changePct/currentPrice/detail）缺省不产出
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.EVENT);
        assertThat(result.getSource()).isEqualTo("事件监控");

        Object itemsObj = result.getData().get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
        assertThat(items).hasSize(2);

        Map<String, Object> first = items.get(0);
        assertThat(first.get("anomalyType")).isEqualTo("PRICE_CHANGE");
        assertThat((BigDecimal) first.get("changePct"))
                .isEqualByComparingTo(new BigDecimal("3.25"));
        assertThat((BigDecimal) first.get("currentPrice"))
                .isEqualByComparingTo(new BigDecimal("1680.50"));
        assertThat(first.get("triggerTime")).isEqualTo(recent.toString());
        assertThat(first.get("detail")).isEqualTo("日涨跌幅 3.25% 触发阈值 3.0%");

        Map<String, Object> second = items.get(1);
        assertThat(second.get("anomalyType")).isEqualTo("EVENT");
        assertThat(second).doesNotContainKeys("changePct", "currentPrice", "detail");
        assertThat(second.get("triggerTime")).isEqualTo(recent.plusSeconds(60).toString());
    }

    // ---- 边界 ----

    @Test
    void fetch_noRecords_returnsMissing() {
        // Arrange：标的近期无任何异动记录（空表/新标的）
        EventSourceAdapter adapter = newAdapter(new FakeAnomalyRepository(List.of()));

        // Act
        SourceResult result = adapter.fetch(subject(1L));

        // Assert：MISSING（成功调用、近期无事件，不阻断），data 为空
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
        assertThat(result.getSourceCode()).isEqualTo(SourceCode.EVENT);
    }

    @Test
    void fetch_allRecordsOutsideWindow_returnsMissing() {
        // Arrange：仅有 7 天窗口外的历史记录 → 过滤后为空 → MISSING
        Instant stale = Instant.now().minus(Duration.ofDays(8));
        FakeAnomalyRepository repository =
                new FakeAnomalyRepository(
                        List.of(
                                AnomalyRecord.reconstruct(
                                        1L,
                                        1L,
                                        AnomalyType.PRICE_CHANGE,
                                        new BigDecimal("4.00"),
                                        null,
                                        stale,
                                        "历史记录",
                                        true,
                                        stale,
                                        stale)));
        EventSourceAdapter adapter = newAdapter(repository);

        // Act
        SourceResult result = adapter.fetch(subject(1L));

        // Assert
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void withinRecentWindow_boundaryIsInclusive() {
        // Arrange：triggerTime 恰等于窗口下界（now - 7d）→ 含边界值算近期；再早 1 秒不算
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        Instant boundary = now.minus(Duration.ofDays(7));
        AnomalyRecord atBoundary =
                AnomalyRecord.reconstruct(
                        1L,
                        1L,
                        AnomalyType.PRICE_CHANGE,
                        null,
                        null,
                        boundary,
                        null,
                        false,
                        null,
                        null);
        AnomalyRecord beforeBoundary =
                AnomalyRecord.reconstruct(
                        1L,
                        1L,
                        AnomalyType.PRICE_CHANGE,
                        null,
                        null,
                        boundary.minusSeconds(1),
                        null,
                        false,
                        null,
                        null);

        // Act + Assert
        assertThat(EventSourceAdapter.withinRecentWindow(atBoundary, now)).isTrue();
        assertThat(EventSourceAdapter.withinRecentWindow(beforeBoundary, now)).isFalse();
    }

    @Test
    void fetch_moreThanMaxItems_truncatesToTen() {
        // Arrange：15 条近期记录 → 截取前 10 条（仓储序即 trigger_time 降序，取最新 10 条）
        Instant base = Instant.now().minus(Duration.ofMinutes(5));
        List<AnomalyRecord> records =
                IntStream.rangeClosed(1, 15)
                        .mapToObj(
                                i ->
                                        AnomalyRecord.reconstruct(
                                                (long) i,
                                                1L,
                                                AnomalyType.PRICE_CHANGE,
                                                BigDecimal.valueOf(i),
                                                null,
                                                base.minusSeconds(i * 60L),
                                                "记录" + i,
                                                false,
                                                base,
                                                base))
                        .toList();
        EventSourceAdapter adapter = newAdapter(new FakeAnomalyRepository(records));

        // Act
        SourceResult result = adapter.fetch(subject(1L));

        // Assert
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getData().get("items");
        assertThat(items).hasSize(10);
    }

    // ---- 异常 ----

    @Test
    void fetch_repositoryThrows_degradesToMissing() {
        // Arrange：本地读表异常（如 DB 不可用）→ 模板弹性降级 MISSING，不外抛、不阻断
        EventSourceAdapter adapter =
                newAdapter(
                        new FakeAnomalyRepository(List.of()) {
                            @Override
                            public List<AnomalyRecord> findBySubjectId(Long subjectId) {
                                throw new IllegalStateException("DB 不可用(测试)");
                            }
                        });

        // Act
        SourceResult result = adapter.fetch(subject(1L));

        // Assert
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    // ---- 装配切换验证（ApplicationContextRunner，不启 Flyway/DB）----

    private static final ApplicationContextRunner WIRING_RUNNER =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withUserConfiguration(
                            SourceAdapterInfrastructureConfig.class,
                            AnomalyRepositoryConfig.class,
                            EventSourceAdapter.class,
                            MockEventSourceAdapter.class);

    @Test
    void mockDisabled_realEventAdapterWired_mockAbsent() {
        WIRING_RUNNER
                .withPropertyValues("adapter.mock.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(EventSourceAdapter.class);
                            assertThat(context).doesNotHaveBean(MockEventSourceAdapter.class);
                        });
    }

    @Test
    void mockEnabled_mockWired_realAdapterAbsent() {
        WIRING_RUNNER
                .withPropertyValues("adapter.mock.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MockEventSourceAdapter.class);
                            assertThat(context).doesNotHaveBean(EventSourceAdapter.class);
                        });
    }

    // ---- helpers ----

    private EventSourceAdapter newAdapter(AnomalyRepository repository) {
        return new EventSourceAdapter(cache, fieldMapper, runner, breaker, repository);
    }

    private static Subject subject(Long id) {
        return Subject.reconstruct(
                id,
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

    /** AnomalyRepository 端口手写 Fake：仅 findBySubjectId 返回构造数据，写路径不支持（本 adapter 只读）。 */
    private static class FakeAnomalyRepository implements AnomalyRepository {

        private final List<AnomalyRecord> records;

        FakeAnomalyRepository(List<AnomalyRecord> records) {
            this.records = records;
        }

        @Override
        public AnomalyRecord save(AnomalyRecord record) {
            throw new UnsupportedOperationException("Fake 不支持写路径");
        }

        @Override
        public boolean existsByBusinessKey(
                Long subjectId, AnomalyType anomalyType, LocalDate triggerDate) {
            throw new UnsupportedOperationException("Fake 不支持查重");
        }

        @Override
        public List<AnomalyRecord> findPending() {
            throw new UnsupportedOperationException("Fake 不支持补推查询");
        }

        @Override
        public List<AnomalyRecord> findBySubjectId(Long subjectId) {
            return records;
        }
    }

    /** 装配测试用：向容器提供 AnomalyRepository 端口 Bean（Fake，只读空表）。 */
    @Configuration
    static class AnomalyRepositoryConfig {

        @Bean
        AnomalyRepository anomalyRepository() {
            return new FakeAnomalyRepository(List.of());
        }
    }
}
