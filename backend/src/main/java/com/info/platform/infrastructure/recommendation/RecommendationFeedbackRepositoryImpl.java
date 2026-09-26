package com.info.platform.infrastructure.recommendation;

import com.info.platform.domain.recommendation.FeedbackAction;
import com.info.platform.domain.recommendation.RecommendationFeedback;
import com.info.platform.domain.recommendation.RecommendationFeedbackRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@link RecommendationFeedbackRepository} 端口的 SQLite 实现（M16 T134，方案 §4.1/§4.7）：append-only
 * 追加（KeyHolder 回填 id）；滚动 30 天 DISLIKE 计数（升级判定查询键 user_id + combo_key + created_at 下界）；同卡同动作 1h 幂等窗口
 * 查询；每卡最近动作批量查询（{@code idx_rf_card}——GROUP BY card_id 取 max(id) 行）。
 */
@Repository
public class RecommendationFeedbackRepositoryImpl implements RecommendationFeedbackRepository {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationFeedbackRepositoryImpl.class);

    private static final String INSERT_SQL =
            """
            INSERT INTO recommendation_feedback (user_id, card_id, action, combo_key, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String COUNT_DISLIKE_SINCE_SQL =
            """
            SELECT COUNT(*)
              FROM recommendation_feedback
             WHERE user_id = ? AND combo_key = ? AND action = 'DISLIKE' AND created_at >= ?
            """;

    private static final String EXISTS_SINCE_SQL =
            """
            SELECT COUNT(*)
              FROM recommendation_feedback
             WHERE user_id = ? AND card_id = ? AND action = ? AND created_at >= ?
            """;

    private static final String FIND_LATEST_BY_CARD_IDS_SQL =
            """
            SELECT card_id, action
              FROM recommendation_feedback
             WHERE card_id IN (%s)
               AND id = (SELECT MAX(id) FROM recommendation_feedback f2 WHERE f2.card_id = recommendation_feedback.card_id)
            """;

    private final JdbcTemplate jdbcTemplate;

    public RecommendationFeedbackRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(RecommendationFeedback feedback) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, feedback.getUserId());
                    ps.setLong(2, feedback.getCardId());
                    ps.setString(3, feedback.getAction().name());
                    ps.setString(4, feedback.getComboKey());
                    ps.setString(5, now.toString());
                    ps.setString(6, now.toString());
                    return ps;
                },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            log.warn(
                    "反馈流水回填 id 失败（append 已生效）: userId={} cardId={} action={}",
                    feedback.getUserId(),
                    feedback.getCardId(),
                    feedback.getAction());
        }
    }

    @Override
    public long countDislikeByUserAndComboSince(long userId, String comboKey, String sinceIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        COUNT_DISLIKE_SINCE_SQL, Long.class, userId, comboKey, sinceIso);
        return count == null ? 0 : count;
    }

    @Override
    public boolean existsSince(long userId, long cardId, FeedbackAction action, Instant since) {
        Long count =
                jdbcTemplate.queryForObject(
                        EXISTS_SINCE_SQL,
                        Long.class,
                        userId,
                        cardId,
                        action.name(),
                        since.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        return count != null && count > 0;
    }

    @Override
    public Map<Long, FeedbackAction> findLatestActionsByCardIds(List<Long> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(cardIds.size(), "?"));
        String sql = FIND_LATEST_BY_CARD_IDS_SQL.formatted(placeholders);
        Map<Long, FeedbackAction> latest = new HashMap<>();
        jdbcTemplate.query(
                sql,
                rs -> {
                    latest.put(
                            rs.getLong("card_id"), FeedbackAction.fromName(rs.getString("action")));
                },
                cardIds.toArray());
        return latest;
    }
}
