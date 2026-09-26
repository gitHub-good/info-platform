package com.info.platform.infrastructure.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link RecommendationCardRepository} 端口的 SQLite 实现（M16 T131，方案 §4.1/§4.5）。
 *
 * <p>建卡 {@code INSERT OR IGNORE}（{@code UNIQUE(user_id, event_id)} 幂等最后防线——同事件重复消费直返
 * 0）；industries/subjects JSON 序列化由本层承担（领域实体保持结构化集合，序列化失败回落空数组不阻断）。推送状态条件 UPDATE 由 T133 推送闸门追加。
 */
@Repository
public class RecommendationCardRepositoryImpl implements RecommendationCardRepository {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationCardRepositoryImpl.class);

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO recommendation_card
              (user_id, event_id, news_id, event_type, importance, direction, level, industries,
               subjects, logic_chain, logic_inputs, gen_method, prompt_version, recscore, basis,
               combo_key, push_status, read, adopted, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, ?, ?)
            """;

    private static final String FIND_BY_USER_AND_EVENT_SQL =
            """
            SELECT id, user_id, event_id, news_id, event_type, importance, direction, level,
                   industries, subjects, logic_chain, logic_inputs, gen_method, prompt_version,
                   recscore, basis, combo_key, push_status, pushed_at, read, adopted,
                   created_at, updated_at
              FROM recommendation_card
             WHERE user_id = ? AND event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    /** 行映射（实例 lambda——subjects/industries 反序列化走实例 ObjectMapper，解析失败回落空表）。 */
    private final RowMapper<RecommendationCard> cardRow =
            (rs, rowNum) ->
                    RecommendationCard.reconstruct(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getLong("event_id"),
                            rs.getLong("news_id"),
                            rs.getString("event_type"),
                            rs.getString("importance"),
                            rs.getString("direction"),
                            RecLevel.fromName(rs.getString("level")),
                            readList(rs.getString("industries")),
                            readSubjects(rs.getString("subjects")),
                            rs.getString("logic_chain"),
                            rs.getString("logic_inputs"),
                            CardGenMethod.fromName(rs.getString("gen_method")),
                            rs.getString("prompt_version"),
                            rs.getDouble("recscore"),
                            rs.getString("basis"),
                            rs.getString("combo_key"),
                            CardPushStatus.fromName(rs.getString("push_status")),
                            instantOrNull(rs.getString("pushed_at")),
                            rs.getInt("read") == 1,
                            rs.getInt("adopted") == 1,
                            instantOrNull(rs.getString("created_at")),
                            instantOrNull(rs.getString("updated_at")));

    public RecommendationCardRepositoryImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public int insertIgnore(RecommendationCard card) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
                INSERT_IGNORE_SQL,
                ps -> {
                    try {
                        bindInsert(ps, card, now);
                    } catch (SQLException e) {
                        throw new IllegalStateException("建卡参数绑定失败: " + e.getMessage(), e);
                    }
                });
    }

    private void bindInsert(PreparedStatement ps, RecommendationCard card, Instant now)
            throws SQLException {
        ps.setLong(1, card.getUserId());
        ps.setLong(2, card.getEventId());
        ps.setLong(3, card.getNewsId());
        ps.setString(4, card.getEventType());
        ps.setString(5, card.getImportance());
        ps.setString(6, card.getDirection());
        ps.setString(7, card.getLevel().name());
        ps.setString(8, writeJson(card.getIndustries()));
        ps.setString(9, writeJson(card.getSubjects()));
        ps.setString(10, card.getLogicChain());
        ps.setString(11, card.getLogicInputs());
        ps.setString(12, card.getGenMethod().name());
        ps.setString(13, card.getPromptVersion());
        ps.setDouble(14, card.getRecscore());
        ps.setString(15, card.getBasis());
        ps.setString(16, card.getComboKey());
        ps.setString(17, now.toString());
        ps.setString(18, now.toString());
    }

    @Override
    public Optional<RecommendationCard> findByUserAndEvent(long userId, long eventId) {
        List<RecommendationCard> cards =
                jdbcTemplate.query(FIND_BY_USER_AND_EVENT_SQL, cardRow, userId, eventId);
        return cards.isEmpty() ? Optional.empty() : Optional.of(cards.get(0));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            log.warn("卡片 JSON 序列化失败（回落空数组）: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("卡片 industries 解析失败（回落空表）: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RecommendationCard.CardSubject> readSubjects(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json, new TypeReference<List<RecommendationCard.CardSubject>>() {});
        } catch (Exception e) {
            log.warn("卡片 subjects 解析失败（回落空表）: {}", e.getMessage());
            return List.of();
        }
    }

    private static Instant instantOrNull(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }
}
