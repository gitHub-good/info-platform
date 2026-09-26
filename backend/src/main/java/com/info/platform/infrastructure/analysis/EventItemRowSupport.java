package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

/**
 * event_item 行映射与 JSON 列解码共享工具（M16 T133 抽取）：EventItemRepositoryImpl 与推荐域 FEED 消费扫描
 * （RecommendationCardRepositoryImpl.findUnconsumedEvents 的 {@code e.*} 列）共用同一份映射——单一事实源防漂移。
 * 无状态纯静态；JSON 回读失败按空表降级（历史行损坏不阻断读，沿 EventItemRepositoryImpl 惯例）。
 */
public final class EventItemRowSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** event_item 行 → {@link EventItem}（列名对齐 V23；时间列整秒 ISO-8601 文本）。 */
    public static final RowMapper<EventItem> EVENT_ROW =
            (rs, rowNum) ->
                    EventItem.reconstruct(
                            rs.getLong("id"),
                            rs.getLong("news_id"),
                            EventType.fromName(rs.getString("event_type")),
                            rs.getString("summary"),
                            jsonList(rs.getString("affected_industries")),
                            Direction.fromName(rs.getString("direction")),
                            Importance.fromName(rs.getString("importance")),
                            jsonFigures(rs.getString("key_figures")),
                            jsonSubjects(rs.getString("subjects")),
                            rs.getString("quote"),
                            nullableInstant(rs.getString("event_time")),
                            rs.getString("event_date"),
                            rs.getString("prompt_version"),
                            nullableInstant(rs.getString("created_at")),
                            nullableInstant(rs.getString("updated_at")));

    private EventItemRowSupport() {}

    private static List<String> jsonList(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<EventItem.KeyFigure> jsonFigures(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<EventItem.SubjectRef> jsonSubjects(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant nullableInstant(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }
}
