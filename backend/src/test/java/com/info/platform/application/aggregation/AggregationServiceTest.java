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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                                Map.of("items", List.of(Map.<String, Object>of("title", "pol1")))));
        AggregationService service =
                new AggregationService(subjectRepository, adapters, syncExecutor, 2000);

        SubjectDetail detail = service.getDetail(1L, Set.of());

        assertThat(detail.sourceStatus()).hasSize(6);
        assertThat(detail.sourceStatus().values()).allMatch("ok"::equals);
        assertThat(detail.quote()).containsEntry("price", "1680.50");
        assertThat(detail.finance()).containsEntry("revenue", "100");
        assertThat(detail.valuation()).containsEntry("pe", "25.5");
        assertThat(detail.announcements()).hasSize(1);
        assertThat(detail.news()).hasSize(1);
        assertThat(detail.policies()).hasSize(1);
        assertThat(detail.subject().subjectCode()).isEqualTo("SH600519");
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
                new AggregationService(subjectRepository, adapters, syncExecutor, 2000);

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
                new AggregationService(subjectRepository, adapters, syncExecutor, 2000);

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
                new AggregationService(subjectRepository, adapters, syncExecutor, 2000);

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
                new AggregationService(subjectRepository, adapters, syncExecutor, 2000);

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
                new AggregationService(subjectRepository, List.of(), syncExecutor, 2000);

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
                            subjectRepository, List.of(slowQuote, fastFinance), exec, 50);

            SubjectDetail detail = service.getDetail(1L, Set.of());

            assertThat(detail.sourceStatus().get("quote")).isEqualTo("timeout");
            assertThat(detail.quote()).isNull();
            assertThat(detail.sourceStatus().get("finance")).isEqualTo("ok");
            assertThat(detail.finance()).isNotNull();
        } finally {
            exec.shutdownNow();
        }
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
