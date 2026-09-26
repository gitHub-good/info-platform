package com.info.platform.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 推荐域实体与枚举守卫单测（T131，方案 §4.1/§4.12）：三实体必填校验与状态语义、五枚举 fromName 白名单、mute 到期判定、反馈 comboKey 条件必填。 AAA
 * 结构，纯 JDK。
 */
class RecommendationDomainEntitiesTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    // ---- 枚举 fromName 白名单 ----

    @Test
    void enums_fromName_roundTripAndRejectUnknown() {
        for (RecLevel level : RecLevel.values()) {
            assertThat(RecLevel.fromName(level.name())).isEqualTo(level);
        }
        for (CardPushStatus status : CardPushStatus.values()) {
            assertThat(CardPushStatus.fromName(status.name())).isEqualTo(status);
        }
        for (CardGenMethod method : CardGenMethod.values()) {
            assertThat(CardGenMethod.fromName(method.name())).isEqualTo(method);
        }
        for (MuteStatus status : MuteStatus.values()) {
            assertThat(MuteStatus.fromName(status.name())).isEqualTo(status);
        }
        for (FeedbackAction action : FeedbackAction.values()) {
            assertThat(FeedbackAction.fromName(action.name())).isEqualTo(action);
        }
        assertThatThrownBy(() -> RecLevel.fromName("P9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardPushStatus.fromName(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardGenMethod.fromName("MAGIC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MuteStatus.fromName("PAUSED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedbackAction.fromName("LIKE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recLevel_coefficientsFrozenByReq() {
        assertThat(RecLevel.P1.coefficient()).isEqualTo(3.0);
        assertThat(RecLevel.P2.coefficient()).isEqualTo(2.0);
        assertThat(RecLevel.P3.coefficient()).isEqualTo(1.0);
    }

    // ---- RecommendationCard ----

    @Test
    void card_createValidatesRequiredFields() {
        RecommendationCard card =
                RecommendationCard.create(
                        7L,
                        9L,
                        11L,
                        "POLICY_RELEASE",
                        "HIGH",
                        "BULLISH",
                        RecLevel.P2,
                        List.of("电子"),
                        List.of(),
                        "逻辑链",
                        null,
                        CardGenMethod.TEMPLATE,
                        null,
                        4.6,
                        "recscore-v1",
                        "POLICY_RELEASE|电子");
        assertThat(card.getPushStatus()).isEqualTo(CardPushStatus.PENDING); // 建卡起点恒 PENDING
        assertThat(card.isRead()).isFalse();
        assertThat(card.isAdopted()).isFalse();
        assertThatThrownBy(
                        () ->
                                RecommendationCard.create(
                                        7L,
                                        9L,
                                        11L,
                                        " ",
                                        "HIGH",
                                        "BULLISH",
                                        RecLevel.P2,
                                        List.of(),
                                        List.of(),
                                        "逻辑链",
                                        null,
                                        CardGenMethod.TEMPLATE,
                                        null,
                                        4.6,
                                        "recscore-v1",
                                        "POLICY_RELEASE|电子"))
                .isInstanceOf(IllegalArgumentException.class); // eventType 必填
        assertThatThrownBy(
                        () ->
                                RecommendationCard.create(
                                        7L,
                                        9L,
                                        11L,
                                        "POLICY_RELEASE",
                                        "HIGH",
                                        "BULLISH",
                                        RecLevel.P2,
                                        List.of(),
                                        List.of(),
                                        " ",
                                        null,
                                        CardGenMethod.TEMPLATE,
                                        null,
                                        4.6,
                                        "recscore-v1",
                                        "POLICY_RELEASE|电子"))
                .isInstanceOf(IllegalArgumentException.class); // logicChain 必填
    }

    // ---- RecommendationFeedback ----

    @Test
    void feedback_comboKeyRequiredForDislikeAndUndoMute() {
        // DISLIKE/UNDO_MUTE 必填 comboKey（升级计数滚动窗查询键）；USEFUL/ADD_WATCHLIST 可空
        assertThat(
                        RecommendationFeedback.append(
                                7L, 1L, FeedbackAction.DISLIKE, "POLICY_RELEASE|电子"))
                .isNotNull();
        assertThat(
                        RecommendationFeedback.append(
                                7L, 1L, FeedbackAction.UNDO_MUTE, "POLICY_RELEASE|电子"))
                .isNotNull();
        assertThatThrownBy(
                        () -> RecommendationFeedback.append(7L, 1L, FeedbackAction.DISLIKE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> RecommendationFeedback.append(7L, 1L, FeedbackAction.UNDO_MUTE, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RecommendationFeedback.append(7L, 1L, FeedbackAction.USEFUL, null)).isNotNull();
    }

    // ---- RecommendationMute ----

    @Test
    void mute_createComputesExpiryAndIsMutingSemantics() {
        RecommendationMute mute = RecommendationMute.create(7L, "POLICY_RELEASE|电子", 7, NOW);
        assertThat(mute.getMutedUntil()).isEqualTo(NOW.plus(java.time.Duration.ofDays(7)));
        assertThat(mute.getTriggerCount()).isEqualTo(1);
        assertThat(mute.isMuting(NOW.plusSeconds(1))).isTrue(); // ACTIVE 且未到期 → 拦截
        assertThat(mute.isMuting(NOW.plus(java.time.Duration.ofDays(8)))).isFalse(); // 到期放行

        // LIFTED 不拦截（撤销语义）
        RecommendationMute lifted =
                RecommendationMute.reconstruct(
                        1L,
                        7L,
                        "POLICY_RELEASE|电子",
                        7,
                        NOW.plus(java.time.Duration.ofDays(7)),
                        1,
                        NOW,
                        MuteStatus.LIFTED,
                        NOW,
                        NOW);
        assertThat(lifted.isMuting(NOW.plusSeconds(1))).isFalse();

        assertThatThrownBy(() -> RecommendationMute.create(7L, " ", 7, NOW))
                .isInstanceOf(IllegalArgumentException.class); // comboKey 必填
    }
}
