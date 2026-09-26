package com.info.platform.infrastructure.recommendation;

import com.info.platform.domain.recommendation.MuteStatus;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link RecommendationMuteRepository} 端口的 SQLite 实现（M16 T133 数据面 / T134 写路径消费，方案 §4.1/§4.7）。
 *
 * <p>常驻状态表（行量有界 ~几十行，不入 retention 清理——ACTIVE 行是有效状态，清理会静默恢复推送）。UPSERT by {@code UNIQUE(user_id,
 * combo_key)}：新组合 INSERT；已有行（含 LIFTED）按入参整体覆盖（reactivate 续期语义，沿 Subscription 模式）；撤销为条件 UPDATE（已
 * LIFTED 返回 0）。
 */
@Repository
public class RecommendationMuteRepositoryImpl implements RecommendationMuteRepository {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationMuteRepositoryImpl.class);

    private static final String UPSERT_SQL =
            """
            INSERT INTO recommendation_mute
              (user_id, combo_key, mute_days, muted_until, trigger_count, last_disliked_at,
               status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id, combo_key) DO UPDATE SET
              mute_days = excluded.mute_days,
              muted_until = excluded.muted_until,
              trigger_count = excluded.trigger_count,
              last_disliked_at = excluded.last_disliked_at,
              status = excluded.status,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_ACTIVE_SQL =
            """
            SELECT id, user_id, combo_key, mute_days, muted_until, trigger_count,
                   last_disliked_at, status, created_at, updated_at
              FROM recommendation_mute
             WHERE user_id = ? AND combo_key = ? AND status = 'ACTIVE'
            """;

    private static final String LIFT_SQL =
            """
            UPDATE recommendation_mute
               SET status = 'LIFTED', updated_at = ?
             WHERE user_id = ? AND combo_key = ? AND status = 'ACTIVE'
            """;

    private static final RowMapper<RecommendationMute> MUTE_ROW =
            (rs, rowNum) ->
                    RecommendationMute.reconstruct(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("combo_key"),
                            rs.getInt("mute_days"),
                            Instant.parse(rs.getString("muted_until")),
                            rs.getInt("trigger_count"),
                            Instant.parse(rs.getString("last_disliked_at")),
                            MuteStatus.fromName(rs.getString("status")),
                            instantOrNull(rs.getString("created_at")),
                            instantOrNull(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public RecommendationMuteRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<RecommendationMute> findActiveByUserAndCombo(long userId, String comboKey) {
        // 到期判定（now < muted_until）由实体 isMuting 承载——查询只收敛 ACTIVE 行
        List<RecommendationMute> mutes =
                jdbcTemplate.query(FIND_ACTIVE_SQL, MUTE_ROW, userId, comboKey);
        return mutes.isEmpty() ? Optional.empty() : Optional.of(mutes.get(0));
    }

    @Override
    public Optional<RecommendationMute> findByUserAndCombo(long userId, String comboKey) {
        // 任意状态（含 LIFTED）——T134 升级判定读现值：triggerCount 增量与 reactivate 续期语义
        List<RecommendationMute> mutes =
                jdbcTemplate.query(FIND_BY_USER_AND_COMBO_SQL, MUTE_ROW, userId, comboKey);
        return mutes.isEmpty() ? Optional.empty() : Optional.of(mutes.get(0));
    }

    private static final String FIND_BY_USER_AND_COMBO_SQL =
            """
            SELECT id, user_id, combo_key, mute_days, muted_until, trigger_count,
                   last_disliked_at, status, created_at, updated_at
              FROM recommendation_mute
             WHERE user_id = ? AND combo_key = ?
            """;

    @Override
    public RecommendationMute upsert(RecommendationMute mute) {
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(UPSERT_SQL);
                    String now = Instant.now().toString();
                    ps.setLong(1, mute.getUserId());
                    ps.setString(2, mute.getComboKey());
                    ps.setInt(3, mute.getMuteDays());
                    ps.setString(4, mute.getMutedUntil().toString());
                    ps.setInt(5, mute.getTriggerCount());
                    ps.setString(6, mute.getLastDislikedAt().toString());
                    ps.setString(7, mute.getStatus().name());
                    ps.setString(8, now);
                    ps.setString(9, now);
                    return ps;
                });
        // 回读落库行（UNIQUE(user_id, combo_key) 收敛单行；入参实体无 id/时间戳）
        List<RecommendationMute> rows =
                jdbcTemplate.query(
                        FIND_BY_USER_AND_COMBO_SQL, MUTE_ROW, mute.getUserId(), mute.getComboKey());
        return rows.isEmpty() ? mute : rows.get(0);
    }

    @Override
    public int liftByUserAndCombo(long userId, String comboKey) {
        int rows = jdbcTemplate.update(LIFT_SQL, Instant.now().toString(), userId, comboKey);
        if (rows == 0) {
            log.debug("撤销降频 no-op（未建/已撤销）: userId={} comboKey={}", userId, comboKey);
        }
        return rows;
    }

    private static Instant instantOrNull(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }
}
