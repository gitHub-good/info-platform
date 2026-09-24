package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * AbstractSourceAdapter 模板方法单测（T02）：缓存命中、字段映射编排、超时降级、重试、熔断闸门、降级钩子。
 *
 * <p>用 {@link FakeSourceAdapter} 注入 doFetch 行为；弹性用虚拟线程执行器（真实超时，非 mock）。 AAA 结构，纯单元（不启 Spring 上下文）。
 */
class AbstractSourceAdapterTest {

    private static final List<FieldMapping> MAPPING =
            List.of(new FieldMapping("price", "price", Transform.TO_DECIMAL));

    @Test
    void fetch_firstCall_fetchesMapsFieldsAndCaches() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s ->
                                    Optional.of(
                                            new RawFetch(
                                                    Map.of("price", "1,680.50"),
                                                    "fake-quote",
                                                    Instant.parse("2026-09-20T03:00:00Z"))),
                            new NoopCircuitBreaker(),
                            null);

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
            assertThat(result.getSourceCode()).isEqualTo(SourceCode.QUOTE);
            assertThat(result.getSubjectId()).isEqualTo(1L);
            assertThat(result.getSource()).isEqualTo("fake-quote");
            assertThat(result.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-20T03:00:00Z"));
            assertThat((BigDecimal) result.getData().get("price"))
                    .isEqualByComparingTo(new BigDecimal("1680.50"));
            assertThat(adapter.callCount.get()).isEqualTo(1);
        }
    }

    @Test
    void fetch_secondCall_hitsCache_skipsDoFetch() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s ->
                                    Optional.of(
                                            new RawFetch(
                                                    Map.of("price", "1"), "fake", Instant.now())),
                            new NoopCircuitBreaker(),
                            null);

            SourceResult first = adapter.fetch(subject(1L));
            SourceResult second = adapter.fetch(subject(1L));

            assertThat(adapter.callCount.get()).as("缓存命中后不再调 doFetch").isEqualTo(1);
            assertThat(second).isSameAs(first);
        }
    }

    @Test
    void fetch_failureCached_secondCallHitsNegativeCacheWithoutRefetch() throws Exception {
        // P1-5b 回归（修前红：FAILED/MISSING 不缓存 → 每次都重新走全链路吃满超时预算）：
        // 首次超时降级入短 TTL 负缓存，TTL 内后续请求快速返回同一降级结果、不再调 doFetch
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofMillis(50)),
                            s -> {
                                Thread.sleep(300);
                                return Optional.of(
                                        new RawFetch(Map.of("price", "1"), "fake", Instant.now()));
                            },
                            new NoopCircuitBreaker(),
                            null);

            SourceResult first = adapter.fetch(subject(1L));
            SourceResult second = adapter.fetch(subject(1L));

            assertThat(first.getStatus()).isEqualTo(SourceStatus.MISSING);
            assertThat(adapter.callCount.get()).as("负缓存命中后不再调 doFetch").isEqualTo(1);
            assertThat(second).isSameAs(first);
        }
    }

    @Test
    void fetch_doFetchTimeout_returnsMissing() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofMillis(50)),
                            s -> {
                                Thread.sleep(300);
                                return Optional.of(
                                        new RawFetch(Map.of("price", "1"), "fake", Instant.now()));
                            },
                            new NoopCircuitBreaker(),
                            null);

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            assertThat(result.getData()).isEmpty();
            assertThat(adapter.callCount.get()).isEqualTo(1);
        }
    }

    @Test
    void fetch_doFetchThrowsAfterRetry_returnsMissing() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.FINANCE,
                            ResilienceSpec.of(Duration.ofMillis(200), 1, Duration.ofMillis(1)),
                            s -> {
                                throw new RuntimeException("boom");
                            },
                            new NoopCircuitBreaker(),
                            null);

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            assertThat(adapter.callCount.get()).as("1 次初始 + 1 次重试").isEqualTo(2);
        }
    }

    @Test
    void fetch_doFetchReturnsEmpty_returnsMissing() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.POLICY,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s -> Optional.empty(),
                            new NoopCircuitBreaker(),
                            null);

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            assertThat(result.getSource()).isEqualTo("政策源");
            assertThat(adapter.callCount.get()).isEqualTo(1);
        }
    }

    @Test
    void fetch_circuitOpen_returnsMissingWithoutDoFetch() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s ->
                                    Optional.of(
                                            new RawFetch(
                                                    Map.of("price", "1"), "fake", Instant.now())),
                            new AlwaysOpenCircuitBreaker(),
                            null);

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            assertThat(adapter.callCount.get()).as("熔断开启时不调 doFetch").isZero();
        }
    }

    @Test
    void fetch_onDegradedOverriddenToFailed_returnsFailed() throws Exception {
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofMillis(50)),
                            s -> {
                                Thread.sleep(300);
                                return Optional.of(
                                        new RawFetch(Map.of("price", "1"), "fake", Instant.now()));
                            },
                            new NoopCircuitBreaker(),
                            (subject, reason) ->
                                    SourceResult.failed(SourceCode.QUOTE, subject.getId(), "行情源"));

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.FAILED);
        }
    }

    @Test
    void resilienceSpec_invalidValues_throw() {
        assertThatThrownBy(() -> new ResilienceSpec(Duration.ZERO, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceSpec(Duration.ofSeconds(1), -1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ResilienceSpec.noRetry(Duration.ofSeconds(2)).maxRetries()).isZero();
    }

    // ---- T16 · data_source_event 旁路记录（降级路径四类触发点 + 记录失败不阻断）----

    @Test
    void fetch_doFetchReturnsEmpty_recordsMissingEvent() throws Exception {
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.POLICY,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s -> Optional.empty(),
                            new NoopCircuitBreaker(),
                            null);
            adapter.dataSourceEventRecorder = recorder;

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            verify(recorder)
                    .recordFailureIfDue(
                            eq(SourceCode.POLICY),
                            eq(DataSourceEventType.MISSING),
                            eq(1L),
                            eq("no-data"));
        }
    }

    @Test
    void fetch_doFetchTimeout_recordsTimeoutEvent() throws Exception {
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofMillis(50)),
                            s -> {
                                Thread.sleep(300);
                                return Optional.of(
                                        new RawFetch(Map.of("price", "1"), "fake", Instant.now()));
                            },
                            new NoopCircuitBreaker(),
                            null);
            adapter.dataSourceEventRecorder = recorder;

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            verify(recorder)
                    .recordFailureIfDue(
                            eq(SourceCode.QUOTE),
                            eq(DataSourceEventType.TIMEOUT),
                            eq(1L),
                            eq("exhausted sourceCode=QUOTE"));
        }
    }

    @Test
    void fetch_doFetchThrowsAfterRetry_recordsErrorEvent() throws Exception {
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.FINANCE,
                            ResilienceSpec.of(Duration.ofMillis(200), 1, Duration.ofMillis(1)),
                            s -> {
                                throw new RuntimeException("boom");
                            },
                            new NoopCircuitBreaker(),
                            null);
            adapter.dataSourceEventRecorder = recorder;

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            verify(recorder)
                    .recordFailureIfDue(
                            eq(SourceCode.FINANCE),
                            eq(DataSourceEventType.ERROR),
                            eq(1L),
                            eq("exhausted sourceCode=FINANCE"));
        }
    }

    @Test
    void fetch_circuitOpen_recordsLimitedEvent() throws Exception {
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofSeconds(1)),
                            s ->
                                    Optional.of(
                                            new RawFetch(
                                                    Map.of("price", "1"), "fake", Instant.now())),
                            new AlwaysOpenCircuitBreaker(),
                            null);
            adapter.dataSourceEventRecorder = recorder;

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
            verify(recorder)
                    .recordFailureIfDue(
                            eq(SourceCode.QUOTE),
                            eq(DataSourceEventType.LIMITED),
                            eq(1L),
                            eq("circuit-open"));
        }
    }

    @Test
    void fetch_recorderThrows_doesNotPropagate_returnsMissing() throws Exception {
        // 记录旁路异常不外抛（defense-in-depth）：recorder 抛异常时 fetch 仍正常降级返回 MISSING
        DataSourceEventRecorder recorder = mock(DataSourceEventRecorder.class);
        doThrow(new RuntimeException("recorder down"))
                .when(recorder)
                .recordFailureIfDue(any(), any(), any(), any());
        try (ExecutorService exec = virtualExecutor()) {
            FakeSourceAdapter adapter =
                    fakeAdapter(
                            exec,
                            SourceCode.QUOTE,
                            ResilienceSpec.noRetry(Duration.ofMillis(50)),
                            s -> {
                                Thread.sleep(300);
                                return Optional.of(
                                        new RawFetch(Map.of("price", "1"), "fake", Instant.now()));
                            },
                            new NoopCircuitBreaker(),
                            null);
            adapter.dataSourceEventRecorder = recorder;

            SourceResult result = adapter.fetch(subject(1L));

            assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        }
    }

    private FakeSourceAdapter fakeAdapter(
            ExecutorService exec,
            SourceCode code,
            ResilienceSpec spec,
            FetchFn fetchFn,
            CircuitBreaker breaker,
            BiFunction<Subject, String, SourceResult> degradedOverride) {
        String label =
                switch (code) {
                    case QUOTE -> "行情源";
                    case FINANCE -> "财务源";
                    case POLICY -> "政策源";
                    default -> code.name() + "源";
                };
        return new FakeSourceAdapter(
                new SourceCache(),
                new FieldMapper(new ObjectMapper()),
                new ResilienceRunner(exec),
                breaker,
                code,
                label,
                MAPPING,
                spec,
                fetchFn,
                degradedOverride);
    }

    private static ExecutorService virtualExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    }

    private static Subject subject(Long id) {
        return Subject.reconstruct(
                id,
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
    }

    /** 熔断常开（allowRequest 恒 false），用于验证熔断闸门直接降级、不调 doFetch。 */
    static final class AlwaysOpenCircuitBreaker implements CircuitBreaker {
        @Override
        public boolean allowRequest(SourceCode code) {
            return false;
        }

        @Override
        public void recordSuccess(SourceCode code) {}

        @Override
        public void recordFailure(SourceCode code) {}
    }

    @FunctionalInterface
    interface FetchFn {
        Optional<RawFetch> apply(Subject subject) throws Exception;
    }

    static final class FakeSourceAdapter extends AbstractSourceAdapter {
        private final SourceCode code;
        private final String label;
        private final List<FieldMapping> mapping;
        private final ResilienceSpec spec;
        private final FetchFn fetchFn;
        private final BiFunction<Subject, String, SourceResult> degradedOverride;
        final AtomicInteger callCount = new AtomicInteger();

        FakeSourceAdapter(
                SourceCache cache,
                FieldMapper fieldMapper,
                ResilienceRunner resilienceRunner,
                CircuitBreaker circuitBreaker,
                SourceCode code,
                String label,
                List<FieldMapping> mapping,
                ResilienceSpec spec,
                FetchFn fetchFn,
                BiFunction<Subject, String, SourceResult> degradedOverride) {
            super(cache, fieldMapper, resilienceRunner, circuitBreaker);
            this.code = code;
            this.label = label;
            this.mapping = mapping;
            this.spec = spec;
            this.fetchFn = fetchFn;
            this.degradedOverride = degradedOverride;
        }

        @Override
        public SourceCode sourceCode() {
            return code;
        }

        @Override
        protected String sourceLabel() {
            return label;
        }

        @Override
        protected List<FieldMapping> mappingConfig() {
            return mapping;
        }

        @Override
        protected ResilienceSpec resilienceSpec() {
            return spec;
        }

        @Override
        protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
            callCount.incrementAndGet();
            return fetchFn.apply(subject);
        }

        @Override
        protected SourceResult onDegraded(Subject subject, String reason) {
            if (degradedOverride != null) {
                return degradedOverride.apply(subject, reason);
            }
            return super.onDegraded(subject, reason);
        }
    }
}
