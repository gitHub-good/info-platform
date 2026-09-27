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
 * AggregationService 编排单测（T09；V2.3-M23 T202 政策分区库内化随批更新）：并行取数成功 / 单源 MISSING 不阻断 / 单源 FAILED 不阻断 /
 * 全失败降级 / sections 过滤 / 超时不阻断 / 标的不存在 30001；POLICY 摘出 fan-out（五源化）—— 政策分区来自
 * SubjectPolicySectionService（mock）恒 ok，POLICY adapter 零调用。
 *
 * <p>纯单元测试：SubjectRepository 用 Mockito，SourceAdapter 用测试替身（控制状态）；同步执行器保证确定性（超时场景用虚拟线程执行器）。
 */
class AggregationServiceTest {

    private final SubjectRepository subjectRepository = Mockito.mock(SubjectRepository.class);

    /** 同步执行器：supplyAsync 在调用线程内联执行，结果立即可用，测试确定性高。 */
    private final Executor syncExecutor = Runnable::run;

    /** 政策分区服务 mock（T202：库内分区数据面；返回可辨别的固定分区对象）。 */
    private final SubjectPolicySectionService policySectionService =
            Mockito.mock(SubjectPolicySectionService.class);

    private PolicySectionView policySection() {
        return new PolicySectionView(
                List.of(
                        new PolicySectionView.Item(
                                4089L,
                                "降准政策",
                                "https://gov/a",
                                "2026-09-20T16:00:00Z",
                                "中国政府网·政策",
                                "SUBJECT")),
                null,
                "policy-scope-v1");
    }

    private AggregationService service(List<SourceAdapter> adapters) {
        return new AggregationService(
                subjectRepository, adapters, syncExecutor, () -> 2000L, policySectionService);
    }

    @Test
    void getDetail_allOk_returnsAllSectionsAndOkStatus() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
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
                        // POLICY adapter 仍在注册表（轨 A 留至 T203）——fan-out 摘除后其数据不被消费
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
        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        assertThat(detail.sourceStatus()).hasSize(7);
        assertThat(detail.sourceStatus().values()).allMatch("ok"::equals);
        assertThat(detail.quote()).containsEntry("price", "1680.50");
        assertThat(detail.finance()).containsEntry("revenue", "100");
        assertThat(detail.valuation()).containsEntry("pe", "25.5");
        assertThat(detail.announcements()).hasSize(1);
        assertThat(detail.news()).hasSize(1);
        // T202：政策分区为分区对象（items+matchType+basis），数据来自库内服务而非 adapter items
        assertThat(detail.policies()).isNotNull();
        assertThat(detail.policies().items()).hasSize(1);
        assertThat(detail.policies().items().get(0).matchType()).isEqualTo("SUBJECT");
        assertThat(detail.policies().basis()).isEqualTo("policy-scope-v1");
        assertThat(detail.events()).hasSize(1);
        assertThat(detail.events().get(0)).containsEntry("anomalyType", "PRICE_CHANGE");
        assertThat(detail.subject().subjectCode()).isEqualTo("SH600519");
    }

    @Test
    void getDetail_policyAdapterNeverCalled_sectionFromInLibraryService() {
        // T202 五源化：POLICY adapter fetch 零调用（fan-out 摘除）；分区数据 = 库内服务返回值
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
        AtomicInteger policyFetchCalls = new AtomicInteger();
        SourceAdapter policyAdapter =
                new SourceAdapter() {
                    @Override
                    public SourceResult fetch(Subject s) {
                        policyFetchCalls.incrementAndGet();
                        return SourceResult.ok(
                                SourceCode.POLICY,
                                s.getId(),
                                Map.of("items", List.of()),
                                "t",
                                Instant.now());
                    }

                    @Override
                    public SourceCode sourceCode() {
                        return SourceCode.POLICY;
                    }
                };
        List<SourceAdapter> adapters =
                List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1")), policyAdapter);

        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        assertThat(policyFetchCalls.get()).isZero();
        assertThat(detail.sourceStatus().get("policy")).isEqualTo("ok");
        assertThat(detail.policies().items()).hasSize(1);
        assertThat(detail.policies().fallback()).isNull();
    }

    @Test
    void getDetail_policySectionFallbackCarried_statusStillOk() {
        // 宏观兜底段形态：items 空 + fallback 非空（MISSING 消除构造），sourceStatus.policy 恒 ok
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519"))
                .thenReturn(
                        new PolicySectionView(
                                List.of(),
                                new PolicySectionView.Fallback(
                                        List.of(
                                                new PolicySectionView.Item(
                                                        9L,
                                                        "近期宏观政策",
                                                        "https://gov/b",
                                                        "2026-09-19T16:00:00Z",
                                                        "中国政府网·政策",
                                                        null)),
                                        SubjectPolicySectionService.FALLBACK_NOTE),
                                "policy-scope-v1"));
        SubjectDetail detail =
                service(List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1"))))
                        .getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("policy")).isEqualTo("ok");
        assertThat(detail.policies().items()).isEmpty();
        assertThat(detail.policies().fallback()).isNotNull();
        assertThat(detail.policies().fallback().note()).isEqualTo("暂无与该标的行业直接相关的政策，以下为近期宏观政策");
        assertThat(detail.policies().fallback().items()).hasSize(1);
    }

    @Test
    void getDetail_eventSourceMissing_statusMissingAndEventsNull() {
        // T08：事件源近期无记录 → MISSING 不阻断，events 分区 null、sourceStatus.event=missing
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                        missingAdapter(SourceCode.EVENT));
        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("event")).isEqualTo("missing");
        assertThat(detail.events()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
    }

    @Test
    void getDetail_singleSourceMissing_doesNotBlockOthers() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
        List<SourceAdapter> adapters =
                Stream.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                                okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                                okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                                okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                                okAdapter(SourceCode.NEWS, Map.of("items", List.of())),
                                missingAdapter(SourceCode.POLICY))
                        .toList();
        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        // T202：POLICY adapter 的 MISSING 不再传导——分区库内化恒 ok（adapter 结果不被消费）
        assertThat(detail.sourceStatus().get("policy")).isEqualTo("ok");
        assertThat(detail.policies()).isNotNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.quote()).isNotNull();
    }

    @Test
    void getDetail_singleSourceFailed_doesNotBlockOthers() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
        List<SourceAdapter> adapters =
                Stream.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                                okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                                okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                                okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                                failedAdapter(SourceCode.NEWS),
                                okAdapter(SourceCode.POLICY, Map.of("items", List.of())))
                        .toList();
        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        assertThat(detail.sourceStatus().get("news")).isEqualTo("failed");
        assertThat(detail.news()).isNull();
        assertThat(detail.sourceStatus().get("quote")).isEqualTo("ok");
        assertThat(detail.sourceStatus().get("policy")).isEqualTo("ok");
    }

    @Test
    void getDetail_allFailedDegraded_returnsAllNonOkStatusWithoutThrowing() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
        List<SourceAdapter> adapters =
                Stream.of(
                                failedAdapter(SourceCode.QUOTE),
                                failedAdapter(SourceCode.FINANCE),
                                failedAdapter(SourceCode.VALUATION),
                                missingAdapter(SourceCode.ANNOUNCE),
                                failedAdapter(SourceCode.NEWS),
                                missingAdapter(SourceCode.POLICY))
                        .toList();
        SubjectDetail detail = service(adapters).getDetail(1L, Set.of());

        assertThat(detail.sourceStatus())
                .containsEntry("quote", "failed")
                .containsEntry("finance", "failed")
                .containsEntry("valuation", "failed")
                .containsEntry("announce", "missing")
                .containsEntry("news", "failed")
                // T202：五源全降级时政策分区仍恒 ok（库内查询无外呼三态，方案 §4.3）
                .containsEntry("policy", "ok");
        assertThat(detail.quote()).isNull();
        assertThat(detail.finance()).isNull();
        assertThat(detail.policies()).isNotNull();
    }

    @Test
    void getDetail_sectionsFilter_onlyFetchesRequestedSections() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        when(policySectionService.sectionOf("SH600519")).thenReturn(policySection());
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                        okAdapter(SourceCode.FINANCE, Map.of("revenue", "1")),
                        okAdapter(SourceCode.VALUATION, Map.of("pe", "1")),
                        okAdapter(SourceCode.ANNOUNCE, Map.of("items", List.of())),
                        okAdapter(SourceCode.NEWS, Map.of("items", List.of())),
                        okAdapter(SourceCode.POLICY, Map.of("items", List.of())));
        SubjectDetail detail =
                service(adapters).getDetail(1L, Set.of(SourceCode.QUOTE, SourceCode.FINANCE));

        assertThat(detail.sourceStatus()).containsOnlyKeys("quote", "finance");
        assertThat(detail.quote()).isNotNull();
        assertThat(detail.finance()).isNotNull();
        assertThat(detail.valuation()).isNull();
        // POLICY 未请求：分区与状态键均不出（sections 过滤语义不变）
        assertThat(detail.policies()).isNull();
        assertThat(detail.sourceStatus()).doesNotContainKey("policy");
        Mockito.verifyNoInteractions(policySectionService);
    }

    @Test
    void getDetail_subjectNotFound_throws30001() {
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());
        AggregationService service = service(List.of());

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
                            subjectRepository,
                            List.of(slowQuote, fastFinance),
                            exec,
                            () -> 50L,
                            policySectionService);

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
        AggregationService service = service(List.of(stockOnlyFinance, defaultQuote));

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
                service(
                        List.of(
                                okAdapter(SourceCode.QUOTE, Map.of("price", "3000")),
                                okAdapter(SourceCode.NEWS, Map.of("items", List.of()))));

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
                service(List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1680.50"))));

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
                service(List.of(okAdapter(SourceCode.QUOTE, Map.of("price", "1"))));

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
        AggregationService service = service(List.of(mixedQuote));

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
                service(List.of(okAdapter(SourceCode.FINANCE, Map.of("revenue", "1"))));

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
        AggregationService service = service(List.of(stockOnlyQuote));

        List<SubjectQuote> quotes = service.getQuotes(List.of(1L));

        assertThat(fetchCalls.get()).isZero();
        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).quote()).isNull();
    }

    // ---- M12：sectionPagination 附加键（T90 公告 / T91 事件） ----

    @Test
    void getDetail_announceOkWithPaginationMeta_exposesSectionPagination() {
        // M12（ADR-0037 决策 3）：公告分区 ok 且 data 携带分页元数据 → 附加键透出首屏总数（搭取数便车，零新增外呼）
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(
                                SourceCode.ANNOUNCE,
                                Map.of(
                                        "items",
                                        List.of(Map.<String, Object>of("title", "ann1")),
                                        "total",
                                        1074L,
                                        "paginationSupported",
                                        true,
                                        "moreUrl",
                                        "https://data.eastmoney.com/notices/stock/600519.html")),
                        okAdapter(
                                SourceCode.EVENT,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.<String, Object>of(
                                                        "anomalyType", "PRICE_CHANGE")),
                                        "total",
                                        37L)));
        AggregationService service = service(adapters);

        SubjectDetail detail = service.getDetail(1L, Set.of(SourceCode.ANNOUNCE, SourceCode.EVENT));

        assertThat(detail.sectionPagination()).isNotNull();
        assertThat(detail.sectionPagination().announce()).isNotNull();
        assertThat(detail.sectionPagination().announce().total()).isEqualTo(1074L);
        assertThat(detail.sectionPagination().announce().paginationSupported()).isTrue();
        assertThat(detail.sectionPagination().announce().moreUrl())
                .isEqualTo("https://data.eastmoney.com/notices/stock/600519.html");
        assertThat(detail.sectionPagination().event()).isNotNull();
        assertThat(detail.sectionPagination().event().total()).isEqualTo(37L);
        // 既有键不受附加键影响（items 照常提取）
        assertThat(detail.announcements()).hasSize(1);
        assertThat(detail.events()).hasSize(1);
    }

    @Test
    void getDetail_noPageableSectionOk_sectionPaginationAbsent() {
        // 全部分区非 ok 或无分页元数据 → 附加键整体 null（非破坏：前端既有消费方零感知）
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L)));
        List<SourceAdapter> adapters =
                List.of(
                        okAdapter(SourceCode.QUOTE, Map.of("price", "1")),
                        missingAdapter(SourceCode.ANNOUNCE),
                        missingAdapter(SourceCode.EVENT));
        AggregationService service = service(adapters);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sectionPagination()).isNull();
        assertThat(detail.sourceStatus().get("announce")).isEqualTo("missing");
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
