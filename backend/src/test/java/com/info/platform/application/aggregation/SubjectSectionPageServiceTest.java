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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * SubjectSectionPageService 编排单测（M12 T90/T91）：公告/事件分区分页取数编排。
 *
 * <p>覆盖：主路径（fetchPage OK → 视图逐字段：items/total/paginationSupported/moreUrl/sourceStatus/source；page/size
 * 回显 + 缺省页大小取端点语义缺省）/ 边界（size 显式值透传、越界页空条目仍 ok）/ 异常（标的不存在 30001、 源 MISSING 降级态、护栏超时
 * timeout 态、未装配 adapter 降级）。SourceAdapter 用 Mockito 替身控制 SourceResult 形态（同一 Subject 实例贯穿
 * stub——Subject 无 equals 按引用匹配）；同步执行器保证确定性，超时场景用异步执行器。
 */
class SubjectSectionPageServiceTest {

    private final SubjectRepository subjectRepository = Mockito.mock(SubjectRepository.class);

    private final SourceAdapter announceAdapter = Mockito.mock(SourceAdapter.class);

    private final SourceAdapter eventAdapter = Mockito.mock(SourceAdapter.class);

    /** 同一 Subject 实例贯穿 stub 与断言（Subject 无 equals，Mockito 按引用匹配参数）。 */
    private final Subject subject = subject();

    /** 同步执行器：supplyAsync 内联执行，测试确定性。 */
    private final Executor syncExecutor = Runnable::run;

    private final AggregationRuntimeSettings settings = () -> 2000L;

    private final SectionPageSettings sectionPageSettings = new SectionPageSettings() {
        @Override
        public int announcePageSize() {
            return 10;
        }

        @Override
        public int newsPageSize() {
            return 20;
        }
    };

    /** 构造即注册 sourceCode（路由 map 键控；mock 缺省 null 会令 toUnmodifiableMap NPE）。 */
    private SourceAdapter announceAdapter() {
        Mockito.when(announceAdapter.sourceCode()).thenReturn(SourceCode.ANNOUNCE);
        return announceAdapter;
    }

    private SourceAdapter eventAdapter() {
        Mockito.when(eventAdapter.sourceCode()).thenReturn(SourceCode.EVENT);
        return eventAdapter;
    }

    private SubjectSectionPageService service() {
        return new SubjectSectionPageService(
                subjectRepository,
                List.of(announceAdapter(), eventAdapter()),
                syncExecutor,
                settings,
                sectionPageSettings);
    }

    // ---- 公告（T90）· 主路径 ----

    @Test
    void announcements_okResult_mapsViewFieldsWithDefaults() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(announceAdapter.fetchPage(subject, 1, 10))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE,
                                1L,
                                Map.of(
                                        "items",
                                        List.of(Map.of("title", "公告1")),
                                        "total",
                                        1074L,
                                        "paginationSupported",
                                        true,
                                        "moreUrl",
                                        "https://data.eastmoney.com/notices/stock/600519.html"),
                                "东方财富公告",
                                Instant.now()));

        AnnouncementPageView view = service().announcements(1L, 1, null);

        assertThat(view.items()).hasSize(1);
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(10); // 缺省 = 运行时 announcePageSize
        assertThat(view.total()).isEqualTo(1074L);
        assertThat(view.paginationSupported()).isTrue();
        assertThat(view.moreUrl())
                .isEqualTo("https://data.eastmoney.com/notices/stock/600519.html");
        assertThat(view.sourceStatus()).isEqualTo("ok");
        assertThat(view.source()).isEqualTo("东方财富公告");
    }

    // ---- 公告（T90）· 边界 ----

    @Test
    void announcements_explicitSizePassesThrough() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(announceAdapter.fetchPage(subject, 3, 25)).thenReturn(okEmptyPageResult());

        AnnouncementPageView view = service().announcements(1L, 3, 25);

        assertThat(view.size()).isEqualTo(25); // 显式页大小回显
        assertThat(view.page()).isEqualTo(3);
        Mockito.verify(announceAdapter).fetchPage(subject, 3, 25);
    }

    @Test
    void announcements_outOfBoundsEmptyItems_stillOkWithTotal() {
        // 越界页：源返回 200 空列表 + total 如实（沿 ADR-0035 越界页语义，前端检测回第 1 页）
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(announceAdapter.fetchPage(subject, 5, 10))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.ANNOUNCE,
                                1L,
                                Map.of(
                                        "items",
                                        List.of(),
                                        "total",
                                        1074L,
                                        "paginationSupported",
                                        true),
                                "东方财富公告",
                                Instant.now()));

        AnnouncementPageView view = service().announcements(1L, 5, null);

        assertThat(view.sourceStatus()).isEqualTo("ok");
        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isEqualTo(1074L);
    }

    // ---- 事件（T91）· 主路径 ----

    @Test
    void events_okResult_mapsViewFieldsWithDefaultSize() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(eventAdapter.fetchPage(subject, 1, 10))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.EVENT,
                                1L,
                                Map.of(
                                        "items",
                                        List.of(Map.of("anomalyType", "PRICE_CHANGE")),
                                        "total",
                                        37L),
                                "事件监控",
                                Instant.now()));

        EventPageView view = service().events(1L, 1, null);

        assertThat(view.items()).hasSize(1);
        assertThat(view.page()).isEqualTo(1);
        assertThat(view.size()).isEqualTo(10); // 缺省 10（原 MAX_ITEMS 语义升级为页大小）
        assertThat(view.total()).isEqualTo(37L); // 窗内精确 count
        assertThat(view.sourceStatus()).isEqualTo("ok");
        assertThat(view.source()).isEqualTo("事件监控");
    }

    @Test
    void events_explicitSizeAndPageTwoPassedThrough() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(eventAdapter.fetchPage(subject, 2, 25))
                .thenReturn(
                        SourceResult.ok(
                                SourceCode.EVENT,
                                1L,
                                Map.of("items", List.of(), "total", 37L),
                                "事件监控",
                                Instant.now()));

        EventPageView view = service().events(1L, 2, 25);

        assertThat(view.page()).isEqualTo(2);
        assertThat(view.size()).isEqualTo(25);
        Mockito.verify(eventAdapter).fetchPage(subject, 2, 25);
    }

    @Test
    void events_emptyWindow_returnsMissingWithZeroTotal() {
        // 空窗：MISSING + total=0（维持聚合页现状 missing 兜底语义，前端不渲染分页条）
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(eventAdapter.fetchPage(subject, 1, 10))
                .thenReturn(SourceResult.missing(SourceCode.EVENT, 1L, "事件监控"));

        EventPageView view = service().events(1L, 1, null);

        assertThat(view.sourceStatus()).isEqualTo("missing");
        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isZero();
    }

    // ---- 异常（公告与事件共用编排骨架，以公告为代表） ----

    @Test
    void announcements_subjectNotFound_throws30001() {
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().announcements(999L, 1, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND);
    }

    @Test
    void events_subjectNotFound_throws30001() {
        when(subjectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().events(999L, 1, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND);
    }

    @Test
    void announcements_sourceMissing_returnsDegradedViewNotHttpError() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(announceAdapter.fetchPage(subject, 2, 10))
                .thenReturn(SourceResult.missing(SourceCode.ANNOUNCE, 1L, "东方财富公告"));

        AnnouncementPageView view = service().announcements(1L, 2, null);

        assertThat(view.sourceStatus()).isEqualTo("missing");
        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isNull();
        assertThat(view.paginationSupported()).isFalse();
        assertThat(view.moreUrl()).isNull();
        assertThat(view.source()).isEqualTo("东方财富公告");
    }

    @Test
    void announcements_guardTimeout_returnsTimeoutView() {
        // 护栏超时：adapter 挂起超过总超时 → sourceStatus=timeout + 空列表（不抛 HTTP 错）
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));
        when(announceAdapter.fetchPage(subject, 1, 10))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(500);
                            return okEmptyPageResult();
                        });
        ExecutorService asyncExec =
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            SubjectSectionPageService service =
                    new SubjectSectionPageService(
                            subjectRepository,
                            List.of(announceAdapter()),
                            asyncExec,
                            () -> 50L, // 50ms 总超时
                            sectionPageSettings);

            AnnouncementPageView view = service.announcements(1L, 1, null);

            assertThat(view.sourceStatus()).isEqualTo("timeout");
            assertThat(view.items()).isEmpty();
            assertThat(view.page()).isEqualTo(1);
            assertThat(view.size()).isEqualTo(10);
        } finally {
            asyncExec.shutdownNow();
        }
    }

    @Test
    void announcements_adapterMissing_degradesToTimeoutView() {
        // 防御：未装配 ANNOUNCE adapter（配置面异常）→ 降级态而非抛出
        SubjectSectionPageService service =
                new SubjectSectionPageService(
                        subjectRepository,
                        List.of(),
                        syncExecutor,
                        settings,
                        sectionPageSettings);
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject));

        AnnouncementPageView view = service.announcements(1L, 1, null);

        assertThat(view.sourceStatus()).isEqualTo("timeout");
        assertThat(view.items()).isEmpty();
    }

    // ---- helpers ----

    private static SourceResult okEmptyPageResult() {
        return SourceResult.ok(
                SourceCode.ANNOUNCE,
                1L,
                Map.of("items", List.of(), "paginationSupported", false),
                "东方财富公告",
                Instant.now());
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
