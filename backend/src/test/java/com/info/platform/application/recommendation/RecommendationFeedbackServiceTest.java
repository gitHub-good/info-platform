package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.info.platform.application.ai.ReadingEventService;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.FeedbackAction;
import com.info.platform.domain.recommendation.MuteStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationFeedback;
import com.info.platform.domain.recommendation.RecommendationFeedbackRepository;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 反馈闭环服务单测（T134，方案 §4.7 四动作 API 语义）：USEFUL（adopted 置 1 + ACT 埋点画像回流）/ DISLIKE（7 天降频 UPSERT + 滚动 30
 * 天 ≥3 次升级 30 天 + reactivate 续期）/ ADD_WATCHLIST（subjectCode 必填校验 + 复用自选仓储 + inWatchlist 刷新）/
 * UNDO_MUTE（LIFTED + no-op 幂等）；同卡同动作 1h 窗口幂等直返；30080 不存在/非本人、30081 参数非法。AAA 结构全 mock。
 */
class RecommendationFeedbackServiceTest {

    private static final long USER_ID = 7L;

    private static final long CARD_ID = 901L;

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private static final String COMBO_KEY = "BUYBACK_CHANGE|食品饮料";

    private RecommendationCardRepository cardRepository;

    private RecommendationFeedbackRepository feedbackRepository;

    private RecommendationMuteRepository muteRepository;

    private ReadingEventService readingEventService;

    private WatchlistRepository watchlistRepository;

    private SubjectRepository subjectRepository;

    private RecommendationFeedbackService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(RecommendationCardRepository.class);
        feedbackRepository = mock(RecommendationFeedbackRepository.class);
        muteRepository = mock(RecommendationMuteRepository.class);
        readingEventService = mock(ReadingEventService.class);
        watchlistRepository = mock(WatchlistRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        service =
                new RecommendationFeedbackService(
                        cardRepository,
                        feedbackRepository,
                        muteRepository,
                        readingEventService,
                        watchlistRepository,
                        subjectRepository,
                        settingsWithDefaults(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(cardRepository.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(feedbackRepository.existsSince(anyLong(), anyLong(), any(), any())).thenReturn(false);
        when(feedbackRepository.countDislikeByUserAndComboSince(anyLong(), any(), any()))
                .thenReturn(0L);
        when(muteRepository.findByUserAndCombo(anyLong(), any())).thenReturn(Optional.empty());
        when(muteRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 键缺失回落代码缺省的 Settings（mutedDays=7/escalatedDays=30/escalateThreshold=3/escalateWindowDays=30）。
     */
    private static RecommendationSettings settingsWithDefaults() {
        com.info.platform.application.common.RuntimeConfigService configService =
                mock(com.info.platform.application.common.RuntimeConfigService.class);
        when(configService.read(any())).thenReturn(Optional.empty());
        return new RecommendationSettings(
                configService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static RecommendationCard card() {
        return RecommendationCard.reconstruct(
                CARD_ID,
                USER_ID,
                9001L,
                8001L,
                "BUYBACK_CHANGE",
                "HIGH",
                "BULLISH",
                RecLevel.P1,
                List.of("食品饮料"),
                List.of(new RecommendationCard.CardSubject("SH600519", "贵州茅台", "食品饮料", false)),
                "贵州茅台公告回购计划——该事件直接涉及你关注的标的贵州茅台。",
                "{}",
                CardGenMethod.TEMPLATE,
                "v1.0",
                6.0,
                "recscore-v1",
                COMBO_KEY,
                CardPushStatus.PUSHED,
                NOW.minusSeconds(60),
                false,
                false,
                NOW.minusSeconds(90),
                NOW.minusSeconds(90));
    }

    // ---- USEFUL ----

    @Test
    void useful_appendsFeedbackAdoptsAndRecordsAct() {
        // Arrange：adopted 条件置 1 成功（0 → 1）
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(1);

        // Act
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "USEFUL", null);

        // Assert：流水 + 采纳 + ACT 埋点（contentRef=cardId，subjectCode=标的区首标的——画像回流）
        assertThat(result.muteUntil()).isNull();
        verify(feedbackRepository).append(argThat(argThatAction(FeedbackAction.USEFUL, null)));
        verify(cardRepository).markAdopted(CARD_ID);
        verify(readingEventService)
                .record(
                        eq(USER_ID),
                        eq("RECOMMENDATION_ACT"),
                        eq(String.valueOf(CARD_ID)),
                        eq("SH600519"),
                        eq(null));
    }

    @Test
    void useful_alreadyAdopted_skipsActDedup() {
        // Arrange：已采纳（条件 UPDATE 匹配 0 行——ACT 与 adopted 同点写入，不重复落埋点）
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(0);

        // Act
        service.feedback(USER_ID, CARD_ID, "USEFUL", null);

        // Assert
        verify(readingEventService, never()).record(anyLong(), any(), any(), any(), any());
    }

    // ---- DISLIKE ----

    @Test
    void dislike_firstTime_mutesSevenDays() {
        // Arrange：滚动窗内（含本次）仅 1 次
        when(feedbackRepository.countDislikeByUserAndComboSince(eq(USER_ID), eq(COMBO_KEY), any()))
                .thenReturn(1L);
        when(muteRepository.upsert(any()))
                .thenAnswer(
                        inv -> {
                            RecommendationMute mute = inv.getArgument(0);
                            return mute;
                        });

        // Act
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "DISLIKE", null);

        // Assert：新建 7 天降频（缺省 mutedDays=7），未升级
        assertThat(result.escalated()).isFalse();
        assertThat(result.muteUntil()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600).toString());
        verify(muteRepository)
                .upsert(
                        argThat(
                                argThatMute(
                                        7, 1, NOW.plusSeconds(7 * 24 * 3600), MuteStatus.ACTIVE)));
    }

    @Test
    void dislike_thirdInRollingWindow_escalatesThirtyDays() {
        // Arrange：滚动 30 天内含本次第 3 次（escalateThreshold=3）
        when(feedbackRepository.countDislikeByUserAndComboSince(eq(USER_ID), eq(COMBO_KEY), any()))
                .thenReturn(3L);
        RecommendationMute existing =
                RecommendationMute.reconstruct(
                        11L,
                        USER_ID,
                        COMBO_KEY,
                        7,
                        NOW.minusSeconds(3600),
                        2,
                        NOW.minusSeconds(3600),
                        MuteStatus.LIFTED,
                        NOW.minusSeconds(3600),
                        NOW.minusSeconds(3600));
        when(muteRepository.findByUserAndCombo(USER_ID, COMBO_KEY))
                .thenReturn(Optional.of(existing));

        // Act
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "DISLIKE", null);

        // Assert：升级 30 天静默 + 已有行 reactivate（triggerCount 3）
        assertThat(result.escalated()).isTrue();
        assertThat(result.muteUntil()).isEqualTo(NOW.plusSeconds(30 * 24 * 3600).toString());
        verify(muteRepository)
                .upsert(
                        argThat(
                                argThatMute(
                                        30,
                                        3,
                                        NOW.plusSeconds(30 * 24 * 3600),
                                        MuteStatus.ACTIVE)));
    }

    @Test
    void dislike_afterLift_reactivatesSevenDays() {
        // Arrange：撤销后再次 DISLIKE（滚动窗 2 次 < 3）——LIFTED 行翻回 ACTIVE 续 7 天
        when(feedbackRepository.countDislikeByUserAndComboSince(eq(USER_ID), eq(COMBO_KEY), any()))
                .thenReturn(2L);
        RecommendationMute lifted =
                RecommendationMute.reconstruct(
                        12L,
                        USER_ID,
                        COMBO_KEY,
                        30,
                        NOW.minusSeconds(8 * 24 * 3600),
                        3,
                        NOW.minusSeconds(8 * 24 * 3600),
                        MuteStatus.LIFTED,
                        NOW.minusSeconds(9 * 24 * 3600),
                        NOW.minusSeconds(8 * 24 * 3600));
        when(muteRepository.findByUserAndCombo(USER_ID, COMBO_KEY)).thenReturn(Optional.of(lifted));

        // Act
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "DISLIKE", null);

        // Assert
        assertThat(result.escalated()).isFalse();
        assertThat(result.muteUntil()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600).toString());
        verify(muteRepository)
                .upsert(
                        argThat(
                                argThatMute(
                                        7, 4, NOW.plusSeconds(7 * 24 * 3600), MuteStatus.ACTIVE)));
        verify(cardRepository, never()).markAdopted(anyLong());
    }

    // ---- UNDO_MUTE ----

    @Test
    void undoMute_liftsAndIsIdempotentNoOp() {
        // Act：撤销（无活跃行也 200 no-op——幂等）
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "UNDO_MUTE", null);

        // Assert：流水留痕 + LIFTED 条件 UPDATE
        assertThat(result.muteUntil()).isNull();
        verify(feedbackRepository)
                .append(argThat(argThatAction(FeedbackAction.UNDO_MUTE, COMBO_KEY)));
        verify(muteRepository).liftByUserAndCombo(USER_ID, COMBO_KEY);
    }

    // ---- ADD_WATCHLIST ----

    @Test
    void addWatchlist_requiresSubjectCode() {
        // Act + Assert：subjectCode 缺失 → 30081
        assertThatThrownBy(() -> service.feedback(USER_ID, CARD_ID, "ADD_WATCHLIST", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_FEEDBACK_INVALID);
        verifyNoInteractions(watchlistRepository);
    }

    @Test
    void addWatchlist_unknownSubject_rejected() {
        // Arrange
        when(subjectRepository.findByCode(SubjectCode.of("SH999999"))).thenReturn(Optional.empty());

        // Act + Assert：标的解析不到 → 30081（不落流水不置采纳）
        assertThatThrownBy(() -> service.feedback(USER_ID, CARD_ID, "ADD_WATCHLIST", "SH999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_FEEDBACK_INVALID);
        verifyNoInteractions(watchlistRepository);
        verify(feedbackRepository, never()).append(any());
    }

    @Test
    void addWatchlist_addsToFirstListAdoptsAndRefreshesFlag() {
        // Arrange：已有清单 + 未在清单 + 采纳首置成功
        Subject subject = mock(Subject.class);
        when(subject.getId()).thenReturn(55L);
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(subject));
        Watchlist first =
                Watchlist.reconstruct(
                        3L,
                        USER_ID,
                        "我的清单",
                        null,
                        WatchlistStatus.ENABLED,
                        List.of(),
                        0,
                        NOW.minusSeconds(3600),
                        NOW.minusSeconds(3600));
        when(watchlistRepository.findAllByOwnerId(USER_ID)).thenReturn(List.of(first));
        when(watchlistRepository.existsItemByWatchlistAndSubject(3L, 55L)).thenReturn(false);
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(1);

        // Act
        service.feedback(USER_ID, CARD_ID, "ADD_WATCHLIST", "SH600519");

        // Assert：加入首个清单 + 采纳 + ACT + 标的区 inWatchlist 刷新
        verify(watchlistRepository).saveItem(any());
        verify(cardRepository).markAdopted(CARD_ID);
        verify(readingEventService)
                .record(eq(USER_ID), eq("RECOMMENDATION_ACT"), eq("901"), eq("SH600519"), eq(null));
        verify(cardRepository)
                .updateSubjects(
                        eq(CARD_ID),
                        argThat(
                                argThatSubjects(
                                        List.of(
                                                new RecommendationCard.CardSubject(
                                                        "SH600519", "贵州茅台", "食品饮料", true)))));
    }

    @Test
    void addWatchlist_alreadyInList_idempotentSuccess() {
        // Arrange：已在清单（幂等 409→200 语义：不重复加行，采纳照常）
        Subject subject = mock(Subject.class);
        when(subject.getId()).thenReturn(55L);
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(subject));
        Watchlist first =
                Watchlist.reconstruct(
                        3L,
                        USER_ID,
                        "我的清单",
                        null,
                        WatchlistStatus.ENABLED,
                        List.of(),
                        0,
                        NOW.minusSeconds(3600),
                        NOW.minusSeconds(3600));
        when(watchlistRepository.findAllByOwnerId(USER_ID)).thenReturn(List.of(first));
        when(watchlistRepository.existsItemByWatchlistAndSubject(3L, 55L)).thenReturn(true);
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(0);

        // Act
        service.feedback(USER_ID, CARD_ID, "ADD_WATCHLIST", "SH600519");

        // Assert：不重复加行；流水照落（动作留痕）
        verify(watchlistRepository, never()).saveItem(any());
        verify(feedbackRepository)
                .append(argThat(argThatAction(FeedbackAction.ADD_WATCHLIST, null)));
    }

    @Test
    void addWatchlist_noList_autoCreatesDefault() {
        // Arrange：用户无清单 → 自动建「默认清单」再加入
        Subject subject = mock(Subject.class);
        when(subject.getId()).thenReturn(55L);
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(subject));
        when(watchlistRepository.findAllByOwnerId(USER_ID)).thenReturn(List.of());
        Watchlist created =
                Watchlist.reconstruct(
                        9L, USER_ID, "默认清单", null, WatchlistStatus.ENABLED, List.of(), 0, NOW, NOW);
        when(watchlistRepository.save(any())).thenReturn(created);
        when(watchlistRepository.existsItemByWatchlistAndSubject(9L, 55L)).thenReturn(false);

        // Act
        service.feedback(USER_ID, CARD_ID, "ADD_WATCHLIST", "SH600519");

        // Assert
        verify(watchlistRepository).save(any());
        verify(watchlistRepository).saveItem(any());
    }

    // ---- 已读 / 幂等 / 权限 ----

    @Test
    void markRead_firstTime_marksReadAdoptsAndRecordsAct() {
        // Arrange：首次已读
        when(cardRepository.markRead(CARD_ID)).thenReturn(1);
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(1);

        // Act
        service.markRead(USER_ID, CARD_ID);

        // Assert：read=1 + 隐式采纳（REQ 拍板六-4：展开即采纳）+ ACT
        verify(cardRepository).markRead(CARD_ID);
        verify(cardRepository).markAdopted(CARD_ID);
        verify(readingEventService)
                .record(eq(USER_ID), eq("RECOMMENDATION_ACT"), eq("901"), eq("SH600519"), eq(null));
    }

    @Test
    void markRead_repeat_isIdempotentNoAct() {
        // Arrange：已读再读（200 直返）
        when(cardRepository.markRead(CARD_ID)).thenReturn(0);
        when(cardRepository.markAdopted(CARD_ID)).thenReturn(0);

        // Act
        service.markRead(USER_ID, CARD_ID);

        // Assert
        verify(readingEventService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void feedback_sameActionWithinOneHour_returnsWithoutSideEffects() {
        // Arrange：同卡同动作 1h 窗口内已有流水（幂等直返）
        when(feedbackRepository.existsSince(
                        eq(USER_ID), eq(CARD_ID), eq(FeedbackAction.USEFUL), any()))
                .thenReturn(true);

        // Act
        RecommendationFeedbackService.FeedbackResult result =
                service.feedback(USER_ID, CARD_ID, "USEFUL", null);

        // Assert：不落第二条流水、不重复置采纳/埋点
        assertThat(result.muteUntil()).isNull();
        verify(feedbackRepository, never()).append(any());
        verify(cardRepository, never()).markAdopted(anyLong());
        verifyNoInteractions(readingEventService);
    }

    @Test
    void feedback_cardNotFoundOrNotOwner_404() {
        // Arrange：卡不存在 / 非本人卡（同一 404 语义）
        when(cardRepository.findById(4040L)).thenReturn(Optional.empty());
        when(cardRepository.findById(4041L))
                .thenReturn(
                        Optional.of(
                                RecommendationCard.reconstruct(
                                        4041L,
                                        99L,
                                        1L,
                                        1L,
                                        "BUYBACK_CHANGE",
                                        "HIGH",
                                        "BULLISH",
                                        RecLevel.P1,
                                        List.of(),
                                        List.of(),
                                        "链",
                                        null,
                                        CardGenMethod.TEMPLATE,
                                        null,
                                        1.0,
                                        "b",
                                        COMBO_KEY,
                                        CardPushStatus.PUSHED,
                                        null,
                                        false,
                                        false,
                                        NOW,
                                        NOW)));

        // Act + Assert
        assertThatThrownBy(() -> service.feedback(USER_ID, 4040L, "USEFUL", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
        assertThatThrownBy(() -> service.feedback(USER_ID, 4041L, "USEFUL", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
        assertThatThrownBy(() -> service.markRead(USER_ID, 4040L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void feedback_unknownAction_400() {
        // Act + Assert：action 非法 → 30081
        assertThatThrownBy(() -> service.feedback(USER_ID, CARD_ID, "LIKE", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_FEEDBACK_INVALID);
        verifyNoInteractions(feedbackRepository, muteRepository, watchlistRepository);
    }

    // ---- ArgumentMatchers 工具（流可读性） ----

    private static org.mockito.ArgumentMatcher<RecommendationFeedback> argThatAction(
            FeedbackAction action, String comboKey) {
        return feedback ->
                feedback.getAction() == action
                        && feedback.getCardId() == CARD_ID
                        && java.util.Objects.equals(feedback.getComboKey(), comboKey);
    }

    private static org.mockito.ArgumentMatcher<RecommendationMute> argThatMute(
            int muteDays, int triggerCount, Instant mutedUntil, MuteStatus status) {
        return mute ->
                mute.getMuteDays() == muteDays
                        && mute.getTriggerCount() == triggerCount
                        && mute.getMutedUntil().equals(mutedUntil)
                        && mute.getStatus() == status;
    }

    private static org.mockito.ArgumentMatcher<List<RecommendationCard.CardSubject>>
            argThatSubjects(List<RecommendationCard.CardSubject> expected) {
        return subjects -> subjects.toString().equals(expected.toString());
    }
}
