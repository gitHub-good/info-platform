package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationContext;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationResult;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.common.User;
import com.info.platform.domain.common.UserRepository;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 推荐事件消费编排单测（T133，方案 §3.2 裁决 2 / §4.6）：每用户 AssociationContext 装配一次复用逐事件、 消费窗口（now−20s 缓冲 / now−24h
 * 补跑窗、FEED_SCAN_CAP 上限）、无关联命中零落卡零推送、建卡后闸门同段执行、幂等冲突不计卡、 段式明细
 * feed=scan/trig/hit/card=llm/tpl/push/mute/quota、单用户异常不阻断整轮。
 */
class RecommendationFeedServiceTest {

    private static final long USER_ID = 7L;

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private UserRepository userRepository;

    private RecommendationAssociationService associationService;

    private RecommendationCardService cardService;

    private RecommendationPushGate pushGate;

    private RecommendationCardRepository cardRepository;

    private RecommendationFeedService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        associationService = mock(RecommendationAssociationService.class);
        cardService = mock(RecommendationCardService.class);
        pushGate = mock(RecommendationPushGate.class);
        cardRepository = mock(RecommendationCardRepository.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        service =
                new RecommendationFeedService(
                        userRepository,
                        associationService,
                        cardService,
                        pushGate,
                        cardRepository,
                        new RecommendationSettings(configService, new ObjectMapper()),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(pushGate.run(USER_ID)).thenReturn(new RecommendationPushGate.GateReport(1, 0, 0));
        when(associationService.buildContext(USER_ID))
                .thenReturn(
                        new AssociationContext(
                                USER_ID, List.of(), Set.of(), List.of(), Set.of(), null));
    }

    private static EventItem event(long id) {
        return EventItem.reconstruct(
                id,
                8000L + id,
                EventType.BUYBACK_CHANGE,
                "贵州茅台公告回购计划",
                List.of("食品饮料"),
                Direction.BULLISH,
                Importance.HIGH,
                List.of(),
                List.of(),
                null,
                NOW,
                "2026-09-22",
                "v1.0",
                NOW,
                NOW);
    }

    private static AssociationResult association() {
        return new AssociationResult(
                RecLevel.P1,
                List.of("食品饮料"),
                List.of(),
                "BUYBACK_CHANGE|食品饮料",
                6.0,
                "recscore-v1:lvl=3|2|1;imp=2|1;pf=1",
                false,
                null);
    }

    private static RecommendationCardRepository.FeedEvent feedEvent(long id) {
        return new RecommendationCardRepository.FeedEvent(event(id), "贵州茅台拟回购");
    }

    private void mockScan(RecommendationCardRepository.FeedEvent... events) {
        when(cardRepository.findUnconsumedEvents(
                        anyLong(), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(events));
    }

    @Test
    void tick_consumptionWindow_appliesBufferAndScanWindow() {
        // Arrange
        mockScan();

        // Act
        service.tick();

        // Assert：createdBefore = now − 20s（落库缓冲防 L2 事务竞态）；createdSince = now − 24h（补跑窗）
        verify(cardRepository)
                .findUnconsumedEvents(
                        eq(USER_ID),
                        eq(Instant.parse("2026-09-22T07:59:40Z")),
                        eq(Instant.parse("2026-09-21T08:00:00Z")),
                        eq(RecommendationFeedService.FEED_SCAN_CAP));
    }

    @Test
    void tick_associationHit_generatesCardAndRunsGate() {
        // Arrange
        RecommendationCardRepository.FeedEvent feedEvent = feedEvent(11L);
        mockScan(feedEvent);
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.of(association()));
        when(cardService.generate(
                        anyLong(), any(EventItem.class), anyString(), any(AssociationResult.class)))
                .thenReturn(
                        new RecommendationCardService.GenerationOutcome(true, CardGenMethod.LLM));

        // Act
        RecommendationFeedService.FeedReport report = service.tick();

        // Assert：命中→建卡（LLM 计 1）；tick 内同段执行推送闸门；明细段式
        verify(cardService)
                .generate(
                        eq(USER_ID),
                        eq(feedEvent.event()),
                        eq("贵州茅台拟回购"),
                        any(AssociationResult.class));
        verify(pushGate).run(USER_ID);
        assertThat(report.detail())
                .isEqualTo("feed=scan:1; trig:1; hit:1; card=llm:1/tpl:0; push:1; mute:0; quota:0");
    }

    @Test
    void tick_noAssociationHit_noCardNoGenerateCall() {
        // Arrange：触发门槛过但三级关联无命中（LOW/无命中 → 不生成卡片零留痕）
        mockScan(feedEvent(11L));
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.empty());

        // Act
        RecommendationFeedService.FeedReport report = service.tick();

        // Assert
        verify(cardService, never())
                .generate(
                        anyLong(), any(EventItem.class), anyString(), any(AssociationResult.class));
        assertThat(report.detail())
                .isEqualTo("feed=scan:1; trig:1; hit:0; card=llm:0/tpl:0; push:1; mute:0; quota:0");
    }

    @Test
    void tick_idempotentConflict_notCountedAsCard() {
        // Arrange：同事件同用户已有卡（insertIgnore 0）——重复消费直返
        mockScan(feedEvent(11L));
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.of(association()));
        when(cardService.generate(
                        anyLong(), any(EventItem.class), anyString(), any(AssociationResult.class)))
                .thenReturn(
                        new RecommendationCardService.GenerationOutcome(
                                false, CardGenMethod.TEMPLATE));

        // Act
        RecommendationFeedService.FeedReport report = service.tick();

        // Assert：幂等冲突不计入 llm/tpl 卡计数（一事件一卡）
        assertThat(report.detail())
                .isEqualTo("feed=scan:1; trig:1; hit:1; card=llm:0/tpl:0; push:1; mute:0; quota:0");
    }

    @Test
    void tick_oneUserFails_othersStillProcessed() {
        // Arrange：两用户，第一个装配上下文抛异常——单用户失败不阻断整轮（段式容错）
        User broken = mock(User.class);
        when(broken.getId()).thenReturn(8L);
        User healthy = mock(User.class);
        when(healthy.getId()).thenReturn(USER_ID);
        when(userRepository.findAll()).thenReturn(List.of(broken, healthy));
        when(associationService.buildContext(8L)).thenThrow(new IllegalStateException("db busy"));
        mockScan();
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.empty());

        // Act
        RecommendationFeedService.FeedReport report = service.tick();

        // Assert：healthy 用户照常消费 + 闸门照常执行
        verify(cardRepository)
                .findUnconsumedEvents(
                        eq(USER_ID), any(Instant.class), any(Instant.class), anyInt());
        verify(pushGate).run(USER_ID);
        assertThat(report.detail()).contains("feed=scan:0");
    }

    @Test
    void tick_contextBuiltOncePerUser_reusedAcrossEvents() {
        // Arrange：单用户多事件——上下文每用户每 tick 装配一次（方案 §4.4）
        mockScan(feedEvent(11L), feedEvent(12L));
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.empty());

        // Act
        service.tick();

        // Assert
        verify(associationService, times(1)).buildContext(USER_ID);
        verify(associationService, times(2))
                .associate(any(EventItem.class), anyString(), any(AssociationContext.class));
    }

    @Test
    void tick_scanPassesTitleFromNewsJoin() {
        // Arrange：标题透传（P3 主题命中面 + 数字白名单来源）
        mockScan(feedEvent(11L));
        when(associationService.associate(
                        any(EventItem.class), anyString(), any(AssociationContext.class)))
                .thenReturn(Optional.of(association()));
        when(cardService.generate(
                        anyLong(), any(EventItem.class), anyString(), any(AssociationResult.class)))
                .thenReturn(
                        new RecommendationCardService.GenerationOutcome(
                                true, CardGenMethod.TEMPLATE));

        // Act
        service.tick();

        // Assert
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(cardService)
                .generate(
                        anyLong(),
                        any(EventItem.class),
                        titleCaptor.capture(),
                        any(AssociationResult.class));
        assertThat(titleCaptor.getValue()).isEqualTo("贵州茅台拟回购");
    }
}
