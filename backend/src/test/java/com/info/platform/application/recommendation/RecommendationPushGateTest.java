package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.push.PushService;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
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
import org.mockito.ArgumentCaptor;

/**
 * 推送闸门单测（T133，方案 §4.6 / REQ「7 天只降推送不杀生成」）：降频拦截（combo_key 活跃 mute → 建卡留中心 + push_record SILENT，不弹
 * SSE）→ recscore DESC 排序 → 日推送上限 10（当日已推计数含 SSE 态，超限建卡 SILENT）→ 通过者经 PushService 既有推送链 + card 条件迁移
 * PUSHED。全 mock，AAA。
 */
class RecommendationPushGateTest {

    private static final long USER_ID = 7L;

    private static final Instant NOW = Instant.parse("2026-09-22T02:00:00Z");

    private RecommendationCardRepository cardRepository;

    private RecommendationMuteRepository muteRepository;

    private PushService pushService;

    private RecommendationPushGate gate;

    @BeforeEach
    void setUp() {
        cardRepository = mock(RecommendationCardRepository.class);
        muteRepository = mock(RecommendationMuteRepository.class);
        pushService = mock(PushService.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        RecommendationSettings settings =
                new RecommendationSettings(configService, new ObjectMapper());
        gate =
                new RecommendationPushGate(
                        cardRepository,
                        muteRepository,
                        pushService,
                        settings,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(cardRepository.markPushed(anyLong(), any(Instant.class))).thenReturn(1);
        when(cardRepository.markSkipped(anyLong(), any(CardPushStatus.class))).thenReturn(1);
        when(cardRepository.countPushedSince(eq(USER_ID), any(Instant.class))).thenReturn(0L);
        when(muteRepository.findActiveByUserAndCombo(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    private static RecommendationCard card(long id, String comboKey, double recscore) {
        return RecommendationCard.reconstruct(
                id,
                USER_ID,
                9000L + id,
                8000L + id,
                "BUYBACK_CHANGE",
                "HIGH",
                "BULLISH",
                RecLevel.P1,
                List.of("食品饮料"),
                List.of(),
                "贵州茅台公告回购计划——该事件直接涉及你关注的标的贵州茅台。",
                null,
                CardGenMethod.TEMPLATE,
                null,
                recscore,
                "recscore-v1:lvl=3|2|1;imp=2|1;pf=1",
                comboKey,
                CardPushStatus.PENDING,
                null,
                false,
                false,
                NOW,
                NOW);
    }

    @Test
    void run_mutedCombo_skipsMutedWithSilentRecord() {
        // Arrange：combo 活跃降频中（REQ 拍板：只降推送不杀生成——卡留推荐中心，通知历史 SILENT 可见）
        RecommendationCard card = card(101L, "BUYBACK_CHANGE|食品饮料", 6.0);
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of(card));
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
                                        com.info.platform.domain.recommendation.MuteStatus.ACTIVE,
                                        NOW,
                                        NOW)));

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert：SKIPPED_MUTED 条件迁移 + SILENT 留痕 + 不走 SSE 推送链
        assertThat(report.muted()).isEqualTo(1);
        assertThat(report.pushed()).isZero();
        verify(cardRepository).markSkipped(101L, CardPushStatus.SKIPPED_MUTED);
        verify(pushService).recordSilent(eq(USER_ID), eq(101L), anyString());
        verify(pushService, never()).pushRecommendation(anyLong(), anyLong(), anyString());
        verify(cardRepository, never()).markPushed(anyLong(), any(Instant.class));
    }

    @Test
    void run_muteExpiredOrLifted_cardStillPushes() {
        // Arrange：mute 已到期（now >= muted_until 不拦截）
        RecommendationCard card = card(101L, "BUYBACK_CHANGE|食品饮料", 6.0);
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of(card));
        when(muteRepository.findActiveByUserAndCombo(USER_ID, "BUYBACK_CHANGE|食品饮料"))
                .thenReturn(
                        Optional.of(
                                RecommendationMute.reconstruct(
                                        1L,
                                        USER_ID,
                                        "BUYBACK_CHANGE|食品饮料",
                                        7,
                                        NOW.minusSeconds(1),
                                        1,
                                        NOW,
                                        com.info.platform.domain.recommendation.MuteStatus.ACTIVE,
                                        NOW,
                                        NOW)));

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert：到期恢复正常推送
        assertThat(report.pushed()).isEqualTo(1);
        verify(cardRepository).markPushed(eq(101L), any(Instant.class));
    }

    @Test
    void run_sortsByRecscoreDescAndPushesWithinQuota() {
        // Arrange：同 tick 3 张 PENDING（recscore 4.0/6.0/5.0）→ 排序 6.0/5.0/4.0，配额内全推
        when(cardRepository.findPendingByUser(USER_ID))
                .thenReturn(
                        List.of(
                                card(1L, "A|食品饮料", 4.0),
                                card(2L, "B|电子", 6.0),
                                card(3L, "C|银行", 5.0)));

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert
        assertThat(report.pushed()).isEqualTo(3);
        var order = org.mockito.Mockito.inOrder(pushService, cardRepository);
        order.verify(pushService).pushRecommendation(eq(USER_ID), eq(2L), anyString());
        order.verify(cardRepository).markPushed(eq(2L), any(Instant.class));
        order.verify(pushService).pushRecommendation(eq(USER_ID), eq(3L), anyString());
        order.verify(cardRepository).markPushed(eq(3L), any(Instant.class));
        order.verify(pushService).pushRecommendation(eq(USER_ID), eq(1L), anyString());
    }

    @Test
    void run_quotaExceeded_tailSkippedQuotaWithSilent() {
        // Arrange：当日已推 8 张（含 SSE 态计数），今日 PENDING 3 张 → 前 2 张推送、第 3 张 SKIPPED_QUOTA + SILENT
        when(cardRepository.findPendingByUser(USER_ID))
                .thenReturn(
                        List.of(
                                card(1L, "A|食品饮料", 6.0),
                                card(2L, "B|电子", 5.0),
                                card(3L, "C|银行", 4.0)));
        when(cardRepository.countPushedSince(eq(USER_ID), any(Instant.class))).thenReturn(8L);

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert：remain = 10 - 8 = 2；尾部只静默不丢（中心可见 + 通知历史 SILENT）
        assertThat(report.pushed()).isEqualTo(2);
        assertThat(report.quotaSkipped()).isEqualTo(1);
        verify(cardRepository).markSkipped(3L, CardPushStatus.SKIPPED_QUOTA);
        verify(pushService).recordSilent(eq(USER_ID), eq(3L), anyString());
        verify(cardRepository, never()).markPushed(eq(3L), any(Instant.class));
    }

    @Test
    void run_quotaAlreadyExhausted_allSilent() {
        // Arrange：当日已推满 10 → 全部 SKIPPED_QUOTA
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of(card(1L, "A|食品", 6.0)));
        when(cardRepository.countPushedSince(eq(USER_ID), any(Instant.class))).thenReturn(10L);

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert
        assertThat(report.pushed()).isZero();
        assertThat(report.quotaSkipped()).isEqualTo(1);
        verify(pushService, never()).pushRecommendation(anyLong(), anyLong(), anyString());
    }

    @Test
    void run_quotaCountsSinceShanghaiMidnight() {
        // Arrange：NOW=2026-09-22T02:00Z = 上海 10:00 → 当日零点 = 2026-09-21T16:00Z
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of(card(1L, "A|食品", 6.0)));

        // Act
        gate.run(USER_ID);

        // Assert：countPushedSince 以上海日界零点为窗（换日自动重置，零恢复代码）
        verify(cardRepository).countPushedSince(USER_ID, Instant.parse("2026-09-21T16:00:00Z"));
    }

    @Test
    void run_summaryLineFormat_truncatedTo80() {
        // Arrange
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of(card(1L, "A|食品", 6.0)));

        // Act
        gate.run(USER_ID);

        // Assert：摘要行 =【动态推荐】{事件类型中文}·{方向词}｜{logicChain}，≤80 字截断
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(pushService).pushRecommendation(eq(USER_ID), eq(1L), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).startsWith("【动态推荐】回购·增持·减持·利好｜贵州茅台公告回购计划");
        assertThat(contentCaptor.getValue().length()).isLessThanOrEqualTo(80);
    }

    @Test
    void run_noPendingCards_noop() {
        // Arrange
        when(cardRepository.findPendingByUser(USER_ID)).thenReturn(List.of());

        // Act
        RecommendationPushGate.GateReport report = gate.run(USER_ID);

        // Assert：遗留 PENDING 重推语义——无 PENDING 零动作零计数
        assertThat(report.pushed()).isZero();
        assertThat(report.muted()).isZero();
        assertThat(report.quotaSkipped()).isZero();
        verify(pushService, never()).pushRecommendation(anyLong(), anyLong(), anyString());
        verify(pushService, never()).recordSilent(anyLong(), anyLong(), anyString());
    }
}
