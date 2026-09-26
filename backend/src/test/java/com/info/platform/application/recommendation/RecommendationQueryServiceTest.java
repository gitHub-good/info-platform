package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.MuteStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 推荐中心读服务单测（T133，方案 §4.8）：四维筛选解析（level/eventType/direction/read，非法 → 30082 字段级）、limit 缺省
 * 20/越界拒绝、游标续页与尾页 null、卡片视图 join 组装（event+news 大字段直出/缺事件防御）、muted 现查、详情 owner 行级权限与 logicInputs
 * 透出、30080 语义。
 */
class RecommendationQueryServiceTest {

    private static final long USER_ID = 7L;

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private RecommendationCardRepository cardRepository;

    private EventItemRepository eventRepository;

    private RecommendationMuteRepository muteRepository;

    private RecommendationQueryService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(RecommendationCardRepository.class);
        eventRepository = mock(EventItemRepository.class);
        muteRepository = mock(RecommendationMuteRepository.class);
        service =
                new RecommendationQueryService(
                        cardRepository,
                        eventRepository,
                        muteRepository,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(muteRepository.findActiveByUserAndCombo(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(eventRepository.findStreamItemsByIds(anyList())).thenReturn(List.of());
    }

    private static RecommendationCard card(long id, long userId, String comboKey) {
        return RecommendationCard.reconstruct(
                id,
                userId,
                9000L + id,
                8000L + id,
                "BUYBACK_CHANGE",
                "HIGH",
                "BULLISH",
                RecLevel.P1,
                List.of("食品饮料"),
                List.of(new RecommendationCard.CardSubject("SH600519", "贵州茅台", "食品饮料", true)),
                "贵州茅台公告回购计划——该事件直接涉及你关注的标的贵州茅台。",
                "{\"level\":\"P1\"}",
                CardGenMethod.TEMPLATE,
                "v1.0",
                6.0,
                "recscore-v1:lvl=3|2|1;imp=2|1;pf=1",
                comboKey,
                CardPushStatus.PUSHED,
                NOW,
                false,
                false,
                NOW,
                NOW);
    }

    private static EventItemRepository.EventStreamItem joinedEvent(long eventId) {
        EventItem event =
                EventItem.reconstruct(
                        eventId,
                        8000L + eventId,
                        EventType.BUYBACK_CHANGE,
                        "贵州茅台公告回购计划，拟回购金额不超过30亿元",
                        List.of("食品饮料"),
                        Direction.BULLISH,
                        Importance.HIGH,
                        List.of(new EventItem.KeyFigure("回购金额上限", "30", "亿元")),
                        List.of(new EventItem.SubjectRef("SH600519", "贵州茅台", "食品饮料")),
                        "拟回购金额不超过30亿元",
                        NOW,
                        "2026-09-22",
                        "v1.0",
                        NOW,
                        NOW);
        return new EventItemRepository.EventStreamItem(
                event, "贵州茅台拟回购不超30亿元", "https://example.com/n/1");
    }

    @Test
    void list_resolvesFilters_passesToRepository() {
        // Arrange
        when(cardRepository.findByUserCursor(eq(USER_ID), any(), eq(null), eq(20)))
                .thenReturn(List.of());
        when(cardRepository.countByUser(eq(USER_ID), any())).thenReturn(0L);

        // Act
        service.list(USER_ID, "p1", "buyback_change", "bullish", "0", null, null);

        // Assert：大小写归一解析（P1/BUYBACK_CHANGE/BULLISH/read=0）+ limit 缺省 20
        org.mockito.ArgumentCaptor<RecommendationCardRepository.CardFilter> filterCaptor =
                org.mockito.ArgumentCaptor.forClass(RecommendationCardRepository.CardFilter.class);
        org.mockito.Mockito.verify(cardRepository)
                .findByUserCursor(eq(USER_ID), filterCaptor.capture(), eq(null), eq(20));
        RecommendationCardRepository.CardFilter filter = filterCaptor.getValue();
        assertThat(filter.level()).isEqualTo(RecLevel.P1);
        assertThat(filter.eventType()).isEqualTo(EventType.BUYBACK_CHANGE);
        assertThat(filter.direction()).isEqualTo(Direction.BULLISH);
        assertThat(filter.read()).isEqualTo(false);
    }

    @Test
    void list_invalidLevel_throws30082() {
        assertThatThrownBy(() -> service.list(USER_ID, "P9", null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_FILTER_INVALID));
    }

    @Test
    void list_invalidEventTypeOrDirectionOrRead_throws30082() {
        assertThatThrownBy(() -> service.list(USER_ID, null, "NOT_A_TYPE", null, null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(USER_ID, null, null, "SIDEWAYS", null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(USER_ID, null, null, null, "2", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void list_limitOutOfRange_rejectedNotTruncated() {
        assertThatThrownBy(() -> service.list(USER_ID, null, null, null, null, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(USER_ID, null, null, null, null, null, 51))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void list_joinsEventData_andMarksMuted() {
        // Arrange：卡 + event join + 活跃降频 → muted=true
        RecommendationCard card = card(9L, USER_ID, "BUYBACK_CHANGE|食品饮料");
        when(cardRepository.findByUserCursor(eq(USER_ID), any(), eq(null), eq(20)))
                .thenReturn(List.of(card));
        when(cardRepository.countByUser(eq(USER_ID), any())).thenReturn(1L);
        when(eventRepository.findStreamItemsByIds(List.of(9009L)))
                .thenReturn(List.of(joinedEvent(9009L)));
        when(muteRepository.findActiveByUserAndCombo(USER_ID, "BUYBACK_CHANGE|食品饮料"))
                .thenReturn(
                        Optional.of(
                                RecommendationMute.reconstruct(
                                        1L,
                                        USER_ID,
                                        "BUYBACK_CHANGE|食品饮料",
                                        7,
                                        NOW.plusSeconds(3600),
                                        1,
                                        NOW,
                                        MuteStatus.ACTIVE,
                                        NOW,
                                        NOW)));

        // Act
        RecommendationCardListView view = service.list(USER_ID, null, null, null, null, null, null);

        // Assert：大字段 join 直出 + muted 现查 + feedbackAction 占位（T134 填充）
        assertThat(view.total()).isEqualTo(1);
        assertThat(view.nextBeforeId()).isNull();
        RecommendationCardListView.CardView item = view.items().get(0);
        assertThat(item.summary()).contains("贵州茅台");
        assertThat(item.newsTitle()).isEqualTo("贵州茅台拟回购不超30亿元");
        assertThat(item.newsUrl()).isEqualTo("https://example.com/n/1");
        assertThat(item.figures()).hasSize(1);
        assertThat(item.quote()).isNotEmpty();
        assertThat(item.subjects().get(0).inWatchlist()).isTrue();
        assertThat(item.muted()).isTrue();
        assertThat(item.pushStatus()).isEqualTo("PUSHED");
        assertThat(item.feedbackAction()).isNull();
    }

    @Test
    void list_eventMissing_degradesGracefully() {
        // Arrange：event 行缺失（异常态）——summary/figures 留空不阻断列表
        when(cardRepository.findByUserCursor(eq(USER_ID), any(), eq(null), eq(20)))
                .thenReturn(List.of(card(9L, USER_ID, "BUYBACK_CHANGE|食品饮料")));
        when(cardRepository.countByUser(eq(USER_ID), any())).thenReturn(1L);

        // Act
        RecommendationCardListView view = service.list(USER_ID, null, null, null, null, null, null);

        // Assert：卡片自身字段（logicChain/subjects）仍在
        assertThat(view.items().get(0).logicChain()).isNotEmpty();
        assertThat(view.items().get(0).summary()).isNull();
        assertThat(view.items().get(0).figures()).isEmpty();
    }

    @Test
    void list_fullPage_returnsNextBeforeId_tailPageNull() {
        // Arrange：页满（=limit）且 total > limit → nextBeforeId = 末条 id
        when(cardRepository.findByUserCursor(eq(USER_ID), any(), eq(null), eq(1)))
                .thenReturn(List.of(card(5L, USER_ID, "A|食品饮料")));
        when(cardRepository.countByUser(eq(USER_ID), any())).thenReturn(3L);

        // Act
        RecommendationCardListView page = service.list(USER_ID, null, null, null, null, null, 1);
        RecommendationCardListView tail = service.list(USER_ID, null, null, null, null, 5L, 1);

        // Assert
        assertThat(page.nextBeforeId()).isEqualTo(5L);
        assertThat(tail.items()).isEmpty();
        assertThat(tail.nextBeforeId()).isNull();
    }

    @Test
    void detail_ownerCard_returnsViewWithLogicInputs() {
        // Arrange
        when(cardRepository.findById(9L)).thenReturn(Optional.of(card(9L, USER_ID, "A|食品饮料")));
        when(eventRepository.findStreamItemsByIds(List.of(9009L)))
                .thenReturn(List.of(joinedEvent(9009L)));

        // Act
        RecommendationCardDetailView detail = service.detail(USER_ID, 9L);

        // Assert：logicInputs 抽检面透出；gen_method/prompt_version 不在视图
        assertThat(detail.card().id()).isEqualTo(9L);
        assertThat(detail.logicInputs()).isEqualTo("{\"level\":\"P1\"}");
    }

    @Test
    void detail_missingOrForeignCard_throws30080() {
        // Arrange：不存在 / 他人卡（同一 404 语义）
        when(cardRepository.findById(424242L)).thenReturn(Optional.empty());
        when(cardRepository.findById(10L))
                .thenReturn(Optional.of(card(10L, USER_ID + 1, "A|食品饮料")));

        // Act + Assert
        assertThatThrownBy(() -> service.detail(USER_ID, 424242L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND));
        assertThatThrownBy(() -> service.detail(USER_ID, 10L))
                .isInstanceOf(BusinessException.class);
    }
}
