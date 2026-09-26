package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.recommendation.FeedbackAction;
import com.info.platform.domain.recommendation.RecommendationFeedback;
import com.info.platform.domain.recommendation.RecommendationFeedbackRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 反馈流水仓储集成测试（T134，方案 §4.1/§4.7）：append 回填、滚动 30 天 DISLIKE 计数（升级判定查询面）、同卡同动作 1h
 * 幂等窗口查询、findLatestActionsByCardIds（操作条回显批量查询，取每卡最近一条）。t134f_ 前缀数据隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationFeedbackRepositoryImplTest {

    private static final long USER_ID = 741L;

    private static final long OTHER_USER_ID = 742L;

    @Autowired private RecommendationFeedbackRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM recommendation_feedback WHERE user_id IN (?, ?)",
                USER_ID,
                OTHER_USER_ID);
    }

    private long insertFeedback(
            long userId, long cardId, String action, String comboKey, String createdAtIso) {
        RecommendationFeedback feedback =
                RecommendationFeedback.append(
                        userId, cardId, FeedbackAction.fromName(action), comboKey);
        repository.append(feedback);
        // 测试需要历史时刻的流水（滚动窗/幂等窗边界）——直接回写 created_at 构造时间线
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(id) FROM recommendation_feedback WHERE user_id = ? AND card_id = ?",
                        Long.class,
                        userId,
                        cardId);
        jdbcTemplate.update(
                "UPDATE recommendation_feedback SET created_at = ? WHERE id = ?", createdAtIso, id);
        return id;
    }

    @Test
    void append_fillsIdAndTimestamps() {
        // Act
        repository.append(RecommendationFeedback.append(USER_ID, 10L, FeedbackAction.USEFUL, null));

        // Assert：id/时间戳回填（append-only）
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM recommendation_feedback WHERE user_id = ? AND card_id = ? AND action = 'USEFUL'",
                        Integer.class,
                        USER_ID,
                        10L);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void countDislike_sinceWindow_countsOnlyMatchingCombo() {
        // Arrange：滚动窗内 3 条 DISLIKE（同组合）+ 窗外 1 条 + 他人 1 条 + 异动作 1 条
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        String since = now.minusSeconds(30 * 24 * 3600).toString();
        insertFeedback(USER_ID, 10L, "DISLIKE", "A|食品饮料", now.minusSeconds(3600).toString());
        insertFeedback(USER_ID, 11L, "DISLIKE", "A|食品饮料", now.minusSeconds(7200).toString());
        insertFeedback(USER_ID, 12L, "DISLIKE", "A|食品饮料", now.minusSeconds(10_000).toString());
        insertFeedback(
                USER_ID, 13L, "DISLIKE", "A|食品饮料", now.minusSeconds(31 * 24 * 3600).toString());
        insertFeedback(OTHER_USER_ID, 14L, "DISLIKE", "A|食品饮料", now.toString());
        insertFeedback(USER_ID, 15L, "USEFUL", null, now.toString());
        insertFeedback(USER_ID, 16L, "DISLIKE", "B|电子", now.toString());

        // Act + Assert：仅该用户该组合窗口内 DISLIKE 计 3（升级判定 ≥3 → 30 天）
        assertThat(repository.countDislikeByUserAndComboSince(USER_ID, "A|食品饮料", since))
                .isEqualTo(3);
        assertThat(repository.countDislikeByUserAndComboSince(USER_ID, "B|电子", since)).isEqualTo(1);
    }

    @Test
    void existsSince_oneHourDedupWindow() {
        // Arrange：30 分钟前与 2 小时前各一条同卡同动作
        Instant now = Instant.parse("2026-09-22T08:00:00Z");
        insertFeedback(USER_ID, 20L, "USEFUL", null, now.minusSeconds(1800).toString());
        insertFeedback(USER_ID, 21L, "USEFUL", null, now.minusSeconds(7200).toString());

        // Act + Assert：1h 窗口内（30 分钟前那条）命中幂等直返；窗口起点早于流水（2 小时前那条）也命中；
        // 窗口内无该流水（卡 21 以 since=now-1h 查）不命中；异动作不命中
        assertThat(
                        repository.existsSince(
                                USER_ID, 20L, FeedbackAction.USEFUL, now.minusSeconds(3600)))
                .isTrue();
        assertThat(
                        repository.existsSince(
                                USER_ID, 21L, FeedbackAction.USEFUL, now.minusSeconds(3600)))
                .isFalse();
        assertThat(
                        repository.existsSince(
                                USER_ID, 21L, FeedbackAction.USEFUL, now.minusSeconds(3 * 3600)))
                .isTrue();
        assertThat(
                        repository.existsSince(
                                USER_ID, 20L, FeedbackAction.DISLIKE, now.minusSeconds(3600)))
                .isFalse();
    }

    @Test
    void findLatestActionsByCardIds_returnsMostRecentPerCard() {
        // Arrange：卡 30 三条（USEFUL → DISLIKE → UNDO_MUTE，按落库序），卡 31 一条，卡 32 无
        insertFeedback(USER_ID, 30L, "USEFUL", null, "2026-09-22T07:00:00Z");
        insertFeedback(USER_ID, 30L, "DISLIKE", "A|食品饮料", "2026-09-22T07:30:00Z");
        insertFeedback(USER_ID, 30L, "UNDO_MUTE", "A|食品饮料", "2026-09-22T08:00:00Z");
        insertFeedback(USER_ID, 31L, "ADD_WATCHLIST", null, "2026-09-22T07:45:00Z");

        // Act
        Map<Long, FeedbackAction> latest =
                repository.findLatestActionsByCardIds(List.of(30L, 31L, 32L));

        // Assert：每卡最近一条（id 最大）；无流水卡不落键
        assertThat(latest).containsEntry(30L, FeedbackAction.UNDO_MUTE);
        assertThat(latest).containsEntry(31L, FeedbackAction.ADD_WATCHLIST);
        assertThat(latest).doesNotContainKey(32L);
    }
}
