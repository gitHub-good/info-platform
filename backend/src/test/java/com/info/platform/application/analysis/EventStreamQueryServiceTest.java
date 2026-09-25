package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * EventStreamQueryService 单测（T127，方案 §4.8）：四维筛选解析与非法值 30079 字段级、limit 缺省 20 与越界拒绝、 游标透传与
 * nextBeforeId 尾页判定、空态、卡片字段完整映射（subjects/figures/quote/newsTitle/newsUrl）。全 mock。AAA。
 */
class EventStreamQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private EventItemRepository repository;
    private EventStreamQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventItemRepository.class);
        service = new EventStreamQueryService(repository);
    }

    private static EventItemRepository.EventStreamItem item(
            long id, String newsTitle, String newsUrl) {
        return new EventItemRepository.EventStreamItem(
                EventItem.reconstruct(
                        id,
                        id + 1000,
                        EventType.POLICY_RELEASE,
                        "央行降准释放流动性",
                        List.of("银行", "非银金融"),
                        Direction.BULLISH,
                        Importance.HIGH,
                        List.of(new EventItem.KeyFigure("存款准备金率", "0.5", "pct")),
                        List.of(
                                new EventItem.SubjectRef("SZ000001", "平安银行", "银行"),
                                new EventItem.SubjectRef(null, "某未回联公司", null)),
                        "下调金融机构存款准备金率 0.5 个百分点",
                        NOW,
                        "2026-09-22",
                        "v1.0",
                        NOW,
                        NOW),
                newsTitle,
                newsUrl);
    }

    @Test
    void list_defaults_passUnfilteredFilterWithDefaultLimit() {
        when(repository.findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20)))
                .thenReturn(List.of(item(9, "央行降准", "https://example.com/n/9")));
        when(repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered()))
                .thenReturn(1L);

        EventStreamView view = service.list(null, null, null, null, null, null);

        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.nextBeforeId()).isNull(); // 尾页（不满页）
        assertThat(view.items()).hasSize(1);
    }

    @Test
    void list_filterMatrix_resolvesAllFourDimensions() {
        EventItemRepository.EventStreamFilter expected =
                new EventItemRepository.EventStreamFilter(
                        EventType.MA_MERGER, "银行", Importance.MEDIUM, Direction.BEARISH);
        when(repository.findStreamItems(eq(expected), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(expected)).thenReturn(0L);

        service.list("MA_MERGER", "银行", "MEDIUM", "BEARISH", null, null);

        verify(repository).findStreamItems(eq(expected), eq(null), eq(20));
        verify(repository).countStreamItems(expected);
    }

    @Test
    void list_blankParams_treatedAsAbsent() {
        // 空串/空白 = 未筛选（下拉「全部」选项回传空串的前端契约）
        when(repository.findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20)))
                .thenReturn(List.of());
        when(repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered()))
                .thenReturn(0L);

        service.list("", "  ", "", "", null, null);

        verify(repository)
                .findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20));
    }

    @Test
    void list_invalidFilters_30079_fieldLevelMessages() {
        assertFilterInvalid(() -> service.list("NOT_A_TYPE", null, null, null, null, null), "type");
        assertFilterInvalid(() -> service.list(null, "宏观", null, null, null, null), "industry");
        assertFilterInvalid(
                () -> service.list(null, null, "CRITICAL", null, null, null), "importance");
        assertFilterInvalid(
                () -> service.list(null, null, null, "SIDEWAYS", null, null), "direction");
    }

    @Test
    void list_limitBounds_default20_rejectedNotTruncated() {
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 51))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, -1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_FILTER_INVALID);
    }

    @Test
    void list_cursorPassedThrough_fullPageGivesNextBeforeId() {
        // 满页 + total > limit → nextBeforeId = 当前页最小 id；beforeId 原样透传
        // （仓储契约 id DESC——页尾即全页最小 id，mock 数据按 DESC 序构造）
        List<EventItemRepository.EventStreamItem> fullPage = new java.util.ArrayList<>();
        for (long id = 80; id >= 61; id--) {
            fullPage.add(item(id, "标题" + id, null));
        }
        when(repository.findStreamItems(
                        any(EventItemRepository.EventStreamFilter.class), eq(100L), eq(20)))
                .thenReturn(fullPage);
        when(repository.countStreamItems(any(EventItemRepository.EventStreamFilter.class)))
                .thenReturn(120L);

        EventStreamView view = service.list(null, null, null, null, 100L, null);

        assertThat(view.items()).hasSize(20);
        assertThat(view.nextBeforeId()).isEqualTo(61L); // id DESC 首页尾 = 全页最小 id
        verify(repository)
                .findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(100L), eq(20));
    }

    @Test
    void list_emptyResult_emptyState() {
        when(repository.findStreamItems(
                        any(EventItemRepository.EventStreamFilter.class), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(repository.countStreamItems(any(EventItemRepository.EventStreamFilter.class)))
                .thenReturn(0L);

        EventStreamView view = service.list(null, null, null, null, 500L, 20);

        assertThat(view.total()).isZero();
        assertThat(view.items()).isEmpty();
        assertThat(view.nextBeforeId()).isNull();
    }

    @Test
    void list_mapsCardFieldsFully() {
        when(repository.findStreamItems(any(), any(), anyInt()))
                .thenReturn(List.of(item(9, "央行宣布降准", "https://example.com/n/9")));
        when(repository.countStreamItems(any())).thenReturn(1L);

        EventStreamView view = service.list(null, null, null, null, null, 20);

        EventStreamView.EventCardView card = view.items().get(0);
        assertThat(card.id()).isEqualTo(9L);
        assertThat(card.eventType()).isEqualTo(EventType.POLICY_RELEASE);
        assertThat(card.summary()).isEqualTo("央行降准释放流动性");
        assertThat(card.industries()).containsExactly("银行", "非银金融");
        assertThat(card.direction()).isEqualTo(Direction.BULLISH);
        assertThat(card.importance()).isEqualTo(Importance.HIGH);
        assertThat(card.figures())
                .singleElement()
                .satisfies(
                        figure -> {
                            assertThat(figure.label()).isEqualTo("存款准备金率");
                            assertThat(figure.value()).isEqualTo("0.5");
                            assertThat(figure.unit()).isEqualTo("pct");
                        });
        assertThat(card.subjects()).hasSize(2);
        assertThat(card.subjects().get(0).code()).isEqualTo("SZ000001");
        assertThat(card.subjects().get(1).code()).isNull(); // 未回联仅留名
        assertThat(card.quote()).isEqualTo("下调金融机构存款准备金率 0.5 个百分点");
        assertThat(card.newsId()).isEqualTo(1009L);
        assertThat(card.newsTitle()).isEqualTo("央行宣布降准");
        assertThat(card.newsUrl()).isEqualTo("https://example.com/n/9");
        assertThat(card.eventTime()).isEqualTo(NOW);
    }

    @Test
    void list_enumParamsCaseInsensitive() {
        // 大小写不敏感（HeatWindow.fromName 同款惯例）
        EventItemRepository.EventStreamFilter expected =
                new EventItemRepository.EventStreamFilter(
                        EventType.EARNINGS_FORECAST, null, Importance.LOW, Direction.NEUTRAL);
        when(repository.findStreamItems(eq(expected), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(expected)).thenReturn(0L);

        service.list("earnings_forecast", null, " low ", "neutral", null, null);

        verify(repository).findStreamItems(eq(expected), eq(null), eq(20));
    }

    private static void assertFilterInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(field)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_FILTER_INVALID);
    }
}
