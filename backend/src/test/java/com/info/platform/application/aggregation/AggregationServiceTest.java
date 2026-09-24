package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AggregationService 编排单测（T09）：并行取数成功 / 单源 MISSING 不阻断 / 单源 FAILED 不阻断 / 全失败降级 / sections 过滤 /
 * 超时不阻断 / 标的不存在 30001。
 *
 * <p>纯单元测试：SubjectRepository 用 Mockito，SourceAdapter 用测试替身（控制状态）；同步执行器保证确定性（超时场景用虚拟线程执行器）。
 */
class AggregationServiceTest {

    private final SubjectRepository subjectRepository = Mockito.mock(SubjectRepository.class);

    /** 同步执行器：supplyAsync 在调用线程内联执行，结果立即可用，测试确定性高。 */
    private final Executor syncExecutor = Runnable::run;

    @Test
    void getDetail_allOk_returnsAllSectionsAndOkStatus() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1680.50")),
                        okAdapter(SourceCode.FINANCE, Map.of("revenue", "100")),
                        okAdapter(SourceCode.VALUATION, Map.of("pe", "25.5")),
                        okAdapter(
                                SourceCode.ANNOUNCE,
                                Map.of("items", List.of(Map.<String, Object>of("title", "ann1")))),
                        okAdapter(
                                SourceCode.NEWS,
                                Map.of("items", List.of(Map.<String, Object>of("title", "news1")))),
                        okAdapter(
                                SourceCode.POLICY,
                                Map.of("items", List.of(Map.<String, Object>of("title", "pol1")))),
                        okAdapter(
                                SourceCode.EVENT,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.<String, Object>of(
                                                        "anomalyType", "PRICE_CHANGE")))));
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus()).hasSize(7);
        assertThat(detail.sourceStatus().values()).allMatch("ok"::equals);
        assertThat(detail.quote()).containsEntry("price", "1680.50");
        assertThat(detail.finance()).containsEntry("revenue", "100");
        assertThat(detail.valuation()).containsEntry("pe", "25.5");
        assertThat(detail.announcements()).hasSize(1);
        assertThat(detail.news()).hasSize(1);
        assertThat(detail.policies()).hasSize(1);
        assertThat(detail.events()).hasSize(1);
        assertThat(detail.events().get(0)).containsEntry("anomalyType", "PRICE_CHANGE");
        assertThat(detail.subject().subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void getDetail_eventSourceMissing_statusMissingAndEventsNull() {
        // T08：事件源近期无记录 → MISSING 不阻断，events 分区 null、sourceStatus.event=missing
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                        missingAdapter(SourceCode.EVENT));
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("event")).isEqualTo("missing");
        assertThat(detail.events()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
    }

    @Test
    void getDetail_singleSourceMissing_doesNotBlockOthers() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                Stream.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                                okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                                okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                                okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                                okAdapter(SourceCode.NEWS, Map.of("items", List.of())),
                                missingAdapter(SourceCode.POLICY))
                        .toList();
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("policy")).isEqualTo("missing");
        assertThat(detail.policies()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.quote()).isNotNull();
    }

    @Test
    void getDetail_singleSourceFailed_doesNotBlockOthers() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                Stream.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                                okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                                okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                                okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                                failedAdapter(SourceCode.NEWS),
                                okAdapter(SourceCode.POLICY, Map.of("items", List.of())))
                        .toList();
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("news")).isEqualTo("failed");
        assertThat(detail.news()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.sourceStatus().get("policy")).isEqualTo("ok");
    }

    @Test
    void getDetail_allFailedDegraded_returnsAllNonOkStatusWithoutThrowing() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                Stream.of(
                                failedAdapter(SourceCode.QUOTE),
                                failedAdapter(SourceCode.FINANCE),
                                failedAdapter(SourceCode.VALUATION),
                                missingAdapter(SourceCode.ANNOUNCE),
                                failedAdapter(SourceCode.NEWS),
                                missingAdapter(SourceCode.POLICY))
                        .toList();
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus())
                .containsEntry("quote", "failed")
                .containsEntry("finance", "failed")
                .containsEntry("valuation", "failed")
                .containsEntry("announce", "missing")
                .containsEntry("news", "failed")
                .containsEntry("policy", "missing");
        assertThat(detail.quote()).isNull();
        assertThat(detail.finance()).isNull();
    }

    @Test
    void getDetail_sectionsFilter_onlyFetchesRequestedSections() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                        okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                        okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                        okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                        okAdapter(SourceCode.NEWS, Map.of("items", List.of())),
                        okAdapter(SourceCode.POLICY, Map.of("items", List.of())));
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, () -> 2000L);

        SubjectDetail detail = service.getDetail(1L, Set.of(SourceCode.QUOTE, SourceCode.FINANCE));

        assertThat(detail.sourceStatus()).containsOnlyKeys("quote", "finance");
        assertThat(detail.quote()).isNotNull();
        assertThat(detail.finance()).isNotNull();
        assertThat(detail.valuation()).isNull();
    }

    @Test
    void getDetail_subjectNotFound_throws30001() {
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());
        AggregationService service =
                new AggregationService(subjectRepository, List.of(), syncExecutor, () -> 2000L);

        assertThatThrownBy(() -> service.getDetail(999L, Set.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND));
    }

    @Test
    void getDetail_sourceTimeout_doesNotBlockOtherSources() {
        ExecutorService exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
            SourceAdapter slowQuote =
                    new SourceAdapter() {
                        @Override
                        public SourceResult fetch(Subject s) {
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return SourceResult.failed(SourceCode.QUOTE, s.getId(), "slow");
                            }
                            return SourceResult.ok(
                                    SourceCode.QUOTE,
                                    s.getId(),
                                    Map.of("price", "1"),
                                    "slow",
                                    Instant.now());
                        }

                        @Override
                        public SourceCode sourceCode() {
                            return SourceCode.QUOTE;
                        }
                    };
            SourceAdapter fastFinance = okAdapter(SourceCode.FINANCE, Map.of("revenue", "1"));
            AggregationService service =
                    new AggregationService(
                            subjectRepository, List.of(slowQuote, fastFinance), exec, () -> 50L);

            SubjectDetail detail = service.getDetail(1L, Set.of());

            assertThat(detail.sourceStatus().get("quote")).isEqualTo("timeout");
            assertThat(detail.quote()).isNull();
            assertThat(detail.sourceStatus().get("finance")).isEqualTo("ok");
            assertThat(detail.finance()).isNotNull();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void getDetail_adapterUnsupportedSubjectType_skipsFetchAndMarksMissing() {
        // Arrange: 板块标的 + 仅支持股票的财务源（T31 注册位收窄）；fetch 计数验证不被调用
        when(subjectRepository.findById(1L))
                .thenReturn(Optional.of(subject(1L, SubjectType.SECTOR)));
        AtomicInteger fetchCalls = new AtomicInteger();
        SourceAdapter stockOnlyFinance =
                new SourceAdapter() {
                    @Override
                    public SourceResult fetch(Subject s) {
                        fetchCalls.incrementAndGet();
                        return SourceResult.ok(
                                SourceCode.FINANCE,
                                s.getId(),
                                Map.of("revenue", "1"),
                                "t",
                                Instant.now());
                    }

                    @Override
                    public SourceCode sourceCode() {
                        return SourceCode.FINANCE;
                    }

                    @Override
                    public Set<SubjectType> supportedSubjectTypes() {
                        return EnumSet.of(SubjectType.STOCK);
                    }
                };
        SourceAdapter defaultQuote = okAdapter(SourceCode.QUOTE, Map.of("price", "1"));
        AggregationService service =
                new AggregationService(
                        subjectRepository,
                        List.of(stockOnlyFinance, defaultQuote),
                        syncExecutor,
                        () -> 2000L);

        // Act
        SubjectDetail detail = service.getDetail(1L, Set.of());

        // Assert: 不适用源不调 fetch、分区按 missing 降级（sourceStatus 契约不变）；默认支持源正常
        assertThat(fetchCalls.get()).isZero();
        assertThat(detail.sourceStatus().get("finance")).isEqualTo("missing");
        assertThat(detail.finance()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.quote()).isNotNull();
    }

    @Test
    void getDetail_defaultSupportedTypes_coverIndexAndSector() {
        // Arrange: 指数标的 + 未覆写注册位的源（端口默认 = 全部已开放类型）
        when(subjectRepository.findById(2L))
                .thenReturn(Optional.of(subject(2L, SubjectType.INDEX)));
        AggregationService service =
                new AggregationService(
                        subjectRepository,
                        List.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "3000")),
                                okAdapter(SourceCode.NEWS, Map.of("items", List.of()))),
                        syncExecutor,
                        () -> 2000L);

        // Act
        SubjectDetail detail = service.getDetail(2L, Set.of());

        // Assert: 默认注册位对指数/板块开放，行为不回归
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.sourceStatus().get("news")).isEqualTo("ok");
    }

    // ---- getQuotes 批量行情（体检 P1-2 自选清单表格列） ----

    @Test
    void getQuotes_allOk_returnsSummaryWithQuoteData() {
        when(subjectRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(subject(1L), subject(2L)));
        AggregationService service =
                new AggregationService(
                        subjectRepository,
                        List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1680.50"))),
                        syncExecutor,
                        () -> 2000L);

        List<SubjectQuote> quotes = service.getQuotes(List.of(1L, 2L));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).id()).isEqualTo(1L);
        assertThat(quotes.get(0).subjectCode()).isEqualTo("SH600519");
        assertThat(quotes.get(0).name()).isEqualTo("贵州茅台");
        assertThat(quotes.get(0).quote()).containsEntry("price", "1680.50");
        assertThat(quotes.get(1).quote()).containsEntry("price", "1680.50");
    }

    @Test
    void getQuotes_unknownIdsSkipped_emptyListWhenNothingResolved() {
        when(subjectRepository.findAllById(List.of(999L))).thenReturn(List.of());
        AggregationService service =
                new AggregationService(
                        subjectRepository,
                        List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1"))),
                        syncExecutor,
                        () -> 2000L);

        assertThat(service.getQuotes(List.of(999L))).isEmpty();
    }

    @Test
    void getQuotes_singleFailure_degradesToNullWithoutBlockingOthers() {
        // 标的 1 的行情 MISSING、标的 2 OK：行 1 quote=null、行 2 正常（任一失败不阻断）
        when(subjectRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(subject(1L), subject(2L)));
        SourceAdapter mixedQuote =
                new SourceAdapter() {
                    @Override
                    public SourceResult fetch(Subject s) {
                        return s.getId() == 1L
                                ? SourceResult.missing(SourceCode.QUOTE, s.getId(), "t")
                                : SourceResult.ok(
                                        SourceCode.QUOTE,
                                        s.getId(),
                                        Map.of("price", "10"),
                                        "t",
                                        Instant.now());
                    }

                    @Override
                    public SourceCode sourceCode() {
                        return SourceCode.QUOTE;
                    }
                };
        AggregationService service =
                new AggregationService(
                        subjectRepository, List.of(mixedQuote), syncExecutor, () -> 2000L);

        List<SubjectQuote> quotes = service.getQuotes(List.of(1L, 2L));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).quote()).isNull();
        assertThat(quotes.get(0).subjectCode()).isEqualTo("SH600519"); // 摘要仍返回
        assertThat(quotes.get(1).quote()).containsEntry("price", "10");
    }

    @Test
    void getQuotes_quoteAdapterMissing_allRowsDegradeToNull() {
        when(subjectRepository.findAllById(List.of(1L))).thenReturn(List.of(subject(1L)));
        AggregationService service =
                new AggregationService(
                        subjectRepository,
                        List.of(okAdapter(SourceCode.FINANCE, Map.of("revenue", "1"))),
                        syncExecutor,
                        () -> 2000L);

        List<SubjectQuote> quotes = service.getQuotes(List.of(1L));

        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).quote()).isNull();
    }

    @Test
    void getQuotes_unsupportedSubjectType_skipsFetchAndQuotesNull() {
        // 板块标的 + 仅支持股票的行情源：不外调 fetch，该行 quote=null（摘要仍返回）
        when(subjectRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(subject(1L, SubjectType.SECTOR)));
        AtomicInteger fetchCalls = new AtomicInteger();
        SourceAdapter stockOnlyQuote =
                new SourceAdapter() {
                    @Override
                    public SourceResult fetch(Subject s) {
                        fetchCalls.incrementAndGet();
                        return SourceResult.ok(
                                SourceCode.QUOTE,
                                s.getId(),
                                Map.of("price", "1"),
                                "t",
                                Instant.now());
                    }

                    @Override
                    public SourceCode sourceCode() {
                        return SourceCode.QUOTE;
                    }

                    @Override
                    public Set<SubjectType> supportedSubjectTypes() {
                        return EnumSet.of(SubjectType.STOCK);
                    }
                };
        AggregationService service =
                new AggregationService(
                        subjectRepository, List.of(stockOnlyQuote), syncExecutor, () -> 2000L);

        List<SubjectQuote> quotes = service.getQuotes(List.of(1L));

        assertThat(fetchCalls.get()).isZero();
        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).quote()).isNull();
    }

    private static Subject subject(Long id) {
        return subject(id, SubjectType.STOCK);
    }

    private static Subject subject(Long id, SubjectType type) {
        return Subject.reconstruct(
                id,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                type,
                "贵州茅台",
                Map.of("tushare", "600519.SH"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }

    private static SourceAdapter okAdapter(SourceCode code, Map<String, Object> data) {
        return new SourceAdapter() {
            @Override
            public SourceResult fetch(Subject s) {
                return SourceResult.ok(code, s.getId(), data, code.name() + "-test", Instant.now());
            }

            @Override
            public SourceCode sourceCode() {
                return code;
            }
        };
    }

    private static SourceAdapter missingAdapter(SourceCode code) {
        return new SourceAdapter() {
            @Override
            public SourceResult fetch(Subject s) {
                return SourceResult.missing(code, s.getId(), code.name() + "-test");
            }

            @Override
            public SourceCode sourceCode() {
                return code;
            }
        };
    }

    private static SourceAdapter failedAdapter(SourceCode code) {
        return new SourceAdapter() {
            @Override
            public SourceResult fetch(Subject s) {
                return SourceResult.failed(code, s.getId(), code.name() + "-test");
            }

            @Override
            public SourceCode sourceCode() {
                return code;
            }
        };
    }
}
