package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IndustryHeatQueryService 单测（T123，方案 §4.8）：榜单组装（窗口缺省 H24 + 护栏徽章 + basis/snapshotAt
 * 脚注）、下钻参数校验（30076： 未知窗口/未知行业/未知 type；limit 1~50 越界拒绝）、游标与页大透传、total 对账。全 mock。AAA 结构。
 */
class IndustryHeatQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private HeatSnapshotRepository repository;
    private PipelineGuardService guardService;
    private IndustryHeatQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(HeatSnapshotRepository.class);
        guardService = mock(PipelineGuardService.class);
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        service =
                new IndustryHeatQueryService(
                        repository,
                        guardService,
                        java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static IndustryHeatSnapshot snapshot(
            String industry, double score, long news, long events) {
        return IndustryHeatSnapshot.reconstruct(
                null,
                industry,
                HeatWindow.H24,
                score,
                score / 2,
                100.0,
                news,
                events,
                "heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h",
                NOW,
                NOW,
                NOW);
    }

    @Test
    void board_defaultsToH24_withGuardBadgeAndFootnotes() {
        when(repository.findBoard(HeatWindow.H24))
                .thenReturn(
                        List.of(
                                snapshot("银行", 30.0, 10, 2),
                                snapshot("房地产", 12.0, 8, 1),
                                snapshot("钢铁", 0.0, 0, 0)));
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);

        IndustryHeatBoardView view = service.board(null);

        assertThat(view.window()).isEqualTo("H24");
        assertThat(view.industries()).hasSize(3);
        assertThat(view.industries().get(0).industry()).isEqualTo("银行");
        assertThat(view.industries().get(0).heatScore()).isEqualTo(30.0);
        assertThat(view.industries().get(0).newsCount()).isEqualTo(10);
        assertThat(view.industries().get(0).eventCount()).isEqualTo(2);
        assertThat(view.pipeline().level()).isEqualTo("DEGRADED"); // 横幅三处同源
        assertThat(view.basis()).isEqualTo("heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h");
        assertThat(view.snapshotAt()).isEqualTo(NOW.toString());
    }

    @Test
    void board_d7WindowResolved() {
        when(repository.findBoard(HeatWindow.D7)).thenReturn(List.of());

        IndustryHeatBoardView view = service.board("d7");

        assertThat(view.window()).isEqualTo("D7");
    }

    @Test
    void board_unknownWindow_30076() {
        assertThatThrownBy(() -> service.board("W1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID);
    }

    @Test
    void items_newsType_passesWindowCursorAndLimit() {
        when(repository.findIndustryNewsItems(
                        eq("银行"), anyString(), anyString(), eq(null), anyInt()))
                .thenReturn(
                        List.of(
                                new HeatSnapshotRepository.IndustryNewsItem(
                                        5L, "央行降准", "新浪财经", NOW, true, "https://example.com/n/5")));
        when(repository.countIndustryNewsItems(eq("银行"), anyString(), anyString())).thenReturn(1L);

        IndustryHeatItemsView view = service.items("银行", "H24", "news", null, 20);

        assertThat(view.type()).isEqualTo("news");
        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).newsId()).isEqualTo(5L);
        assertThat(view.items().get(0).hasEvent()).isTrue();
        // T162 trace-v1 A 级：news 行 url 透传（前端标题外链数据面）
        assertThat(view.items().get(0).url()).isEqualTo("https://example.com/n/5");
        assertThat(view.nextBeforeId()).isNull(); // 尾页无下一页游标
    }

    @Test
    void items_defaultsAndPaginationCursor() {
        // type 缺省 news；beforeId/limit 透传；满页给 nextBeforeId（当前页最小 id）
        when(repository.findIndustryNewsItems(eq("银行"), anyString(), anyString(), eq(100L), eq(20)))
                .thenReturn(
                        java.util.stream.LongStream.rangeClosed(81, 100)
                                .mapToObj(id -> id)
                                .sorted(java.util.Comparator.reverseOrder())
                                .map(
                                        id ->
                                                new HeatSnapshotRepository.IndustryNewsItem(
                                                        id,
                                                        "条目" + id,
                                                        "源",
                                                        NOW,
                                                        false,
                                                        "https://example.com/n/" + id))
                                .toList());
        when(repository.countIndustryNewsItems(eq("银行"), anyString(), anyString()))
                .thenReturn(120L);

        IndustryHeatItemsView view = service.items("银行", null, null, 100L, null);

        assertThat(view.window()).isEqualTo("H24");
        assertThat(view.type()).isEqualTo("news");
        assertThat(view.total()).isEqualTo(120L);
        assertThat(view.items()).hasSize(20);
        assertThat(view.nextBeforeId()).isEqualTo(81L);
        verify(repository)
                .findIndustryNewsItems(eq("银行"), anyString(), anyString(), eq(100L), eq(20));
    }

    @Test
    void items_eventsType_sameScopeAsEventStream() {
        when(repository.findIndustryEventItems(
                        eq("银行"), anyString(), anyString(), eq(null), anyInt()))
                .thenReturn(
                        List.of(
                                new HeatSnapshotRepository.IndustryEventItem(
                                        9L,
                                        5L,
                                        "央行降准",
                                        EventType.POLICY_RELEASE,
                                        "降准释放流动性",
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        NOW,
                                        "https://example.com/n/5",
                                        "降准 0.5 个百分点",
                                        "新浪财经")));
        when(repository.countIndustryEventItems(eq("银行"), anyString(), anyString())).thenReturn(1L);

        IndustryHeatItemsView view = service.items("银行", "H24", "events", null, 20);

        assertThat(view.type()).isEqualTo("events");
        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.items().get(0).eventId()).isEqualTo(9L);
        assertThat(view.items().get(0).eventType()).isEqualTo(EventType.POLICY_RELEASE);
        assertThat(view.items().get(0).importance()).isEqualTo(Importance.HIGH);
        // T162 trace-v1：events 行 newsUrl（A 级）+ quote/sourceName（B 级）映射入 ItemView
        assertThat(view.items().get(0).newsUrl()).isEqualTo("https://example.com/n/5");
        assertThat(view.items().get(0).quote()).isEqualTo("降准 0.5 个百分点");
        assertThat(view.items().get(0).sourceName()).isEqualTo("新浪财经");
    }

    @Test
    void items_unknownIndustryOrTypeOrWindow_30076() {
        assertThatThrownBy(() -> service.items("宏观", "H24", "news", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID);
        assertThatThrownBy(() -> service.items("银行", "W1", "news", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID);
        assertThatThrownBy(() -> service.items("银行", "H24", "hot", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID);
    }

    @Test
    void items_limitBounds_rejectedNotTruncated() {
        assertThatThrownBy(() -> service.items("银行", "H24", "news", null, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.items("银行", "H24", "news", null, 51))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void drilldown_emptyPage_noNpeReturnEmptyView() {
        // BUG-02 修复回归：空页（该行业窗内无条目）最后一页判断传 null 曾拆箱 NPE 致 500（8/8 复现）
        IndustryHeatItemsView news = service.items("美容护理", "H24", "news", null, 20);
        assertThat(news.total()).isZero();
        assertThat(news.nextBeforeId()).isNull();
        IndustryHeatItemsView events = service.items("美容护理", "H24", "events", null, 20);
        assertThat(events.total()).isZero();
        assertThat(events.nextBeforeId()).isNull();
    }
}
