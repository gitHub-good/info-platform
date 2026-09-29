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

import com.info.platform.domain.aggregation.Market;
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
 *
 * <p>M29 P1-01 回归（方案 §5.4）：market 过滤维——market=HK/US 传入筛选（事件关联标的含该市场标的）、market 缺省/A_SHARE =
 * 全量零回归、非法 market 30079、industry 校验按 market 口径（HK/US 各自枚举）、页码模式响应 industryFilterGroups 三市场分组。
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

        EventStreamView view = service.list(null, null, null, null, null, null, null);

        assertThat(view.total()).isEqualTo(1L);
        assertThat(view.nextBeforeId()).isNull(); // 尾页（不满页）
        assertThat(view.items()).hasSize(1);
    }

    @Test
    void list_filterMatrix_resolvesAllFourDimensions() {
        EventItemRepository.EventStreamFilter expected =
                new EventItemRepository.EventStreamFilter(
                        EventType.MA_MERGER, "银行", Importance.MEDIUM, Direction.BEARISH, null);
        when(repository.findStreamItems(eq(expected), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(expected)).thenReturn(0L);

        service.list("MA_MERGER", "银行", "MEDIUM", "BEARISH", null, null, null);

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

        service.list("", "  ", "", "", " ", null, null);

        verify(repository)
                .findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20));
    }

    @Test
    void list_invalidFilters_30079_fieldLevelMessages() {
        assertFilterInvalid(
                () -> service.list("NOT_A_TYPE", null, null, null, null, null, null), "type");
        assertFilterInvalid(
                () -> service.list(null, "宏观", null, null, null, null, null), "industry");
        assertFilterInvalid(
                () -> service.list(null, null, "CRITICAL", null, null, null, null), "importance");
        assertFilterInvalid(
                () -> service.list(null, null, null, "SIDEWAYS", null, null, null), "direction");
    }

    @Test
    void list_limitBounds_default20_rejectedNotTruncated() {
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 51))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, -1))
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

        EventStreamView view = service.list(null, null, null, null, null, 100L, null);

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

        EventStreamView view = service.list(null, null, null, null, null, 500L, 20);

        assertThat(view.total()).isZero();
        assertThat(view.items()).isEmpty();
        assertThat(view.nextBeforeId()).isNull();
    }

    @Test
    void list_mapsCardFieldsFully() {
        when(repository.findStreamItems(any(), any(), anyInt()))
                .thenReturn(List.of(item(9, "央行宣布降准", "https://example.com/n/9")));
        when(repository.countStreamItems(any())).thenReturn(1L);

        EventStreamView view = service.list(null, null, null, null, null, null, 20);

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
                        EventType.EARNINGS_FORECAST, null, Importance.LOW, Direction.NEUTRAL, null);
        when(repository.findStreamItems(eq(expected), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(expected)).thenReturn(0L);

        service.list("earnings_forecast", null, " low ", "neutral", null, null, null);

        verify(repository).findStreamItems(eq(expected), eq(null), eq(20));
    }

    // ---- M29 P1-01 回归（方案 §5.4）：market 过滤维 ----

    @Test
    void list_marketHK_passesSubjectMarketFilter_bothModes() {
        // Arrange：market=HK → 筛选维携带 HK（事件关联标的含港股标的——subjects 前缀过滤）
        EventItemRepository.EventStreamFilter expected =
                new EventItemRepository.EventStreamFilter(null, null, null, null, Market.HK);
        when(repository.findStreamItems(eq(expected), eq(null), eq(20))).thenReturn(List.of());
        when(repository.countStreamItems(expected)).thenReturn(6L);

        // Act：游标模式
        service.list(null, null, null, null, "HK", null, null);
        // Act：页码模式（同筛选同口径）
        service.listPaged(null, null, null, null, "hk", 1, 20);

        // Assert：两模式同 filter（total 与分页同口径）；大小写不敏感
        verify(repository).findStreamItems(eq(expected), eq(null), eq(20));
        verify(repository).findStreamItemsPaged(expected, 1, 20);
        verify(repository, org.mockito.Mockito.times(2)).countStreamItems(expected);
    }

    @Test
    void list_marketAShareOrAbsent_unfiltered_zeroRegression() {
        // Arrange：market 显式 A_SHARE = 缺省全量语义（A 股视角零回归——缺省与显式同 filter）
        when(repository.findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20)))
                .thenReturn(List.of());
        when(repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered()))
                .thenReturn(0L);

        // Act
        service.list(null, null, null, null, "A_SHARE", null, null);
        service.list(null, null, null, null, "a_share", null, null);

        // Assert：显式 A_SHARE 两形态均等价 unfiltered（market 维 null）
        verify(repository, org.mockito.Mockito.times(2))
                .findStreamItems(
                        eq(EventItemRepository.EventStreamFilter.unfiltered()), eq(null), eq(20));
    }

    @Test
    void list_invalidMarket_30079_fieldLevel() {
        // Arrange/Act/Assert：非法市场枚举 30079 字段级（INDEX/SECTOR 非事件流口径同样拒绝）
        assertFilterInvalid(() -> service.list(null, null, null, null, "JP", null, null), "market");
        assertFilterInvalid(
                () -> service.list(null, null, null, null, "INDEX", null, null), "market");
        assertFilterInvalid(
                () -> service.listPaged(null, null, null, null, "NYSE", 1, 20), "market");
    }

    @Test
    void list_industryValidation_marketAware() {
        // Arrange：market=HK + 港股枚举行业 → 合法（filter.industry=软件服务, filter.market=HK）
        EventItemRepository.EventStreamFilter hkIndustry =
                new EventItemRepository.EventStreamFilter(null, "软件服务", null, null, Market.HK);
        when(repository.findStreamItems(eq(hkIndustry), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(hkIndustry)).thenReturn(0L);

        // Act
        service.list(null, "软件服务", null, null, "HK", null, null);

        // Assert：HK 枚举 + HK 市场维组合下发
        verify(repository).findStreamItems(eq(hkIndustry), eq(null), eq(20));

        // Assert：申万行业在 HK 口径下非法（跨市场枚举不混用）；港股枚举在缺省 A 股口径下仍非法（零回归）
        assertFilterInvalid(
                () -> service.list(null, "计算机", null, null, "HK", null, null), "industry");
        assertFilterInvalid(
                () -> service.list(null, "软件服务", null, null, null, null, null), "industry");

        // Assert：US 口径接受美股枚举
        EventItemRepository.EventStreamFilter usIndustry =
                new EventItemRepository.EventStreamFilter(null, "制药", null, null, Market.US);
        when(repository.findStreamItems(eq(usIndustry), eq(null), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(usIndustry)).thenReturn(0L);
        service.list(null, "制药", null, null, "US", null, null);
        verify(repository).findStreamItems(eq(usIndustry), eq(null), eq(20));
    }

    @Test
    void listPaged_industryFilterGroups_threeMarkets() {
        // Arrange：页码模式响应恒带三市场行业分组（前端过滤器下拉消费——缺省回 SW 31 容错）
        when(repository.findStreamItemsPaged(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(repository.countStreamItems(any())).thenReturn(0L);

        // Act
        EventStreamPageView view = service.listPaged(null, null, null, null, null, 1, 20);

        // Assert：三组不混排（拍板二）；A_SHARE=申万 31（SW_ENUM_ORDER 同序）/ HK=31 直采 / US=40 归并；UNKNOWN 与容器不入选
        assertThat(view.industryFilterGroups())
                .extracting(EventStreamPageView.IndustryFilterGroupView::market)
                .containsExactly("A_SHARE", "HK", "US");
        List<EventStreamPageView.IndustryFilterGroupView> groups = view.industryFilterGroups();
        assertThat(groups.get(0).industries())
                .containsExactlyElementsOf(ClassificationService.SW_ENUM_ORDER)
                .hasSize(31);
        assertThat(groups.get(1).industries()).hasSize(31).doesNotContain("UNKNOWN");
        assertThat(groups.get(2).industries()).hasSize(40).doesNotContain("UNKNOWN");
        // 确定性：两次构建输出序一致（Set 无序 → Collator 固定序，防响应漂移）
        assertThat(EventStreamPageView.INDUSTRY_FILTER_GROUPS)
                .isEqualTo(view.industryFilterGroups());
    }

    private static void assertFilterInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(field)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_FILTER_INVALID);
    }

    // ---- T220（M25 V3.0）：page/size 页码模式（offset 语义，total 与游标模式同口径）----

    @Test
    void listPaged_passesFilterPageAndSize_totalSameAsCursorMode() {
        // Arrange：四维筛选 + 页码/页大小透传仓储；total 沿 countStreamItems（与游标模式同源）
        EventItemRepository.EventStreamFilter expected =
                new EventItemRepository.EventStreamFilter(
                        EventType.POLICY_RELEASE, null, Importance.HIGH, null, null);
        when(repository.findStreamItemsPaged(expected, 3, 10))
                .thenReturn(List.of(item(9, "央行降准", "https://example.com/n/9")));
        when(repository.countStreamItems(expected)).thenReturn(28L);

        // Act
        EventStreamPageView view =
                service.listPaged("POLICY_RELEASE", null, "HIGH", null, null, 3, 10);

        // Assert：page/size 如实回显，total 与游标模式同一计数口
        assertThat(view.total()).isEqualTo(28L);
        assertThat(view.page()).isEqualTo(3);
        assertThat(view.size()).isEqualTo(10);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).id()).isEqualTo(9L);
        verify(repository).findStreamItemsPaged(expected, 3, 10);
        verify(repository).countStreamItems(expected);
    }

    @Test
    void listPaged_invalidFilters_30079_sameAsCursorMode() {
        // 页码模式同样走四维筛选解析（非法枚举字段级 30079）
        assertFilterInvalid(
                () -> service.listPaged("NOT_A_TYPE", null, null, null, null, 1, 20), "type");
    }
}
