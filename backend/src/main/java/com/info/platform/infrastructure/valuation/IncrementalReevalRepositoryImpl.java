package com.info.platform.infrastructure.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link IncrementalReevalRepository} 端口的 SQLite 实现（M22 T190，V33 表）：扫描 = LEFT JOIN 判重 + 窗界 + 重要度 IN
 * 集合（枚举序由应用侧展开——SQL 不承载枚举知识）；写路径全列直 UPDATE（一轮一事件一行）；挂起轮 = judge_passed=1 且状态 IN (RECOMPUTED,
 * DEFERRED)。表 50 行量级全扫毫秒级（方案 §3.5-4 不加索引）。
 */
@Repository
public class IncrementalReevalRepositoryImpl implements IncrementalReevalRepository {

    /** 扫描 SQL（方案 §4.3.1 伪码形态：LEFT JOIN 判重 + 窗界 + 缓冲）。 */
    private static final String SCAN_SQL =
            """
            SELECT e.id AS event_id, e.created_at, e.summary, e.importance, e.subjects,
                   e.affected_industries
              FROM event_item e
              LEFT JOIN incremental_reeval_log l ON l.event_id = e.id
             WHERE l.id IS NULL
               AND e.created_at <= ? AND e.created_at >= ?
               AND e.importance IN (%s)
             ORDER BY e.id ASC
             LIMIT ?
            """;

    private static final String INSERT_SCANNED_SQL =
            """
            INSERT OR IGNORE INTO incremental_reeval_log
              (event_id, event_created_at, subjects_json, judge_passed, status, created_at, updated_at)
            VALUES (?, ?, '[]', 0, 'SCANNED', ?, ?)
            """;

    private static final String MARK_RECOMPUTED_SQL =
            """
            UPDATE incremental_reeval_log
               SET subjects_json = ?, snapshot_at = COALESCE(?, snapshot_at),
                   judge_passed = ?, status = 'RECOMPUTED', updated_at = ?
             WHERE event_id IN (%s)
            """;

    private static final String MARK_STATUS_SQL =
            "UPDATE incremental_reeval_log SET status = ?, updated_at = ? WHERE event_id IN (%s)";

    private static final String MARK_LINKED_SQL =
            """
            UPDATE incremental_reeval_log
               SET status = 'LINKED', top_version = ?, top_version_at = ?, updated_at = ?
             WHERE event_id IN (%s)
            """;

    private static final String MARK_FAILED_SQL =
            """
            UPDATE incremental_reeval_log
               SET status = 'FAILED', error_message = ?, updated_at = ?
             WHERE event_id IN (%s)
            """;

    private static final String PENDING_LINK_SQL =
            """
            SELECT event_id, subjects_json FROM incremental_reeval_log
             WHERE judge_passed = 1 AND status IN ('RECOMPUTED', 'DEFERRED')
             ORDER BY id ASC
            """;

    private static final String LAST_EVENT_VERSION_SQL =
            """
            SELECT b.created_at FROM market_top_batch b
             WHERE b.rank_date = ? AND b.trigger_source = 'EVENT'
             ORDER BY b.version DESC LIMIT 1
            """;

    private static final String SCORES_SQL =
            """
            SELECT s.subject_id, m.subject_code, s.total_score
              FROM subject_factor_snapshot s
              JOIN subject_master m ON m.id = s.subject_id
             WHERE s.snapshot_date = ? AND s.subject_id IN (%s)
             ORDER BY s.subject_id ASC
            """;

    /** 增量轮事件反查（M22 T192：snapshot_at 精确命中的轮内事件 + event_item 摘要面——一轮多事件同刻）。 */
    private static final String ROUND_EVENTS_SQL =
            """
            SELECT l.event_id, e.summary, e.importance, e.event_date
              FROM incremental_reeval_log l
              LEFT JOIN event_item e ON e.id = l.event_id
             WHERE l.snapshot_at = ?
             ORDER BY l.event_id ASC
            """;

    private static final RowMapper<ReevalEvent> EVENT_ROW =
            (rs, rowNum) ->
                    new ReevalEvent(
                            rs.getLong("event_id"),
                            rs.getString("created_at"),
                            rs.getString("summary"),
                            Importance.fromName(rs.getString("importance")),
                            codesOf(rs.getString("subjects")),
                            stringsOf(rs.getString("affected_industries")));

    private static final RowMapper<PendingLink> PENDING_ROW =
            (rs, rowNum) -> new PendingLink(rs.getLong("event_id"), rs.getString("subjects_json"));

    private static final RowMapper<SubjectScore> SCORE_ROW =
            (rs, rowNum) ->
                    new SubjectScore(
                            rs.getLong("subject_id"),
                            rs.getString("subject_code"),
                            rs.getDouble("total_score"));

    private static final RowMapper<RoundEvent> ROUND_EVENT_ROW =
            (rs, rowNum) ->
                    new RoundEvent(
                            rs.getLong("event_id"),
                            rs.getString("summary"),
                            rs.getString("importance"),
                            rs.getString("event_date"));

    private final JdbcTemplate jdbcTemplate;

    public IncrementalReevalRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ReevalEvent> findUnconsumedEvents(
            Importance minImportance, Instant createdBefore, Instant createdSince, int limit) {
        // 重要度 ≥ 阈值（枚举序：coefficient HIGH 1.0 > MEDIUM 0.5 > LOW 0.25）→ IN 集合由应用侧展开
        List<String> allowed = new ArrayList<>();
        for (Importance importance : Importance.values()) {
            if (importance.coefficient() >= minImportance.coefficient()) {
                allowed.add(importance.name());
            }
        }
        Object[] args = new Object[2 + allowed.size() + 1];
        args[0] = createdBefore.toString();
        args[1] = createdSince.toString();
        for (int i = 0; i < allowed.size(); i++) {
            args[2 + i] = allowed.get(i);
        }
        args[args.length - 1] = limit;
        return jdbcTemplate.query(
                SCAN_SQL.formatted(placeholders(allowed.size())), EVENT_ROW, args);
    }

    @Override
    public int insertScanned(ReevalEvent event, String nowIso) {
        return jdbcTemplate.update(
                INSERT_SCANNED_SQL, event.eventId(), event.eventCreatedAtIso(), nowIso, nowIso);
    }

    @Override
    public int markRecomputed(
            Collection<Long> eventIds,
            String subjectsJson,
            String snapshotAtIso,
            boolean judgePassed,
            String nowIso) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                MARK_RECOMPUTED_SQL.formatted(placeholders(eventIds.size())),
                appendTail(
                        new Object[] {subjectsJson, snapshotAtIso, judgePassed ? 1 : 0, nowIso},
                        eventIds));
    }

    @Override
    public int markStatus(Collection<Long> eventIds, String status, String nowIso) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                MARK_STATUS_SQL.formatted(placeholders(eventIds.size())),
                appendTail(new Object[] {status, nowIso}, eventIds));
    }

    @Override
    public int markLinked(
            Collection<Long> eventIds, int topVersion, String topVersionAtIso, String nowIso) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                MARK_LINKED_SQL.formatted(placeholders(eventIds.size())),
                appendTail(new Object[] {topVersion, topVersionAtIso, nowIso}, eventIds));
    }

    @Override
    public int markFailed(Collection<Long> eventIds, String errorMessage, String nowIso) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
                MARK_FAILED_SQL.formatted(placeholders(eventIds.size())),
                appendTail(new Object[] {errorMessage, nowIso}, eventIds));
    }

    @Override
    public List<PendingLink> findPendingLink() {
        return jdbcTemplate.query(PENDING_LINK_SQL, PENDING_ROW);
    }

    @Override
    public Optional<String> findLastEventVersionAt(String rankDate) {
        return jdbcTemplate
                .query(LAST_EVENT_VERSION_SQL, (rs, rowNum) -> rs.getString(1), rankDate)
                .stream()
                .findFirst();
    }

    @Override
    public List<SubjectScore> findScoresBySubjectIds(
            String snapshotDate, java.util.Collection<Long> subjectIds) {
        List<Long> distinct = subjectIds.stream().distinct().sorted().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                SCORES_SQL.formatted(placeholders(distinct.size())),
                SCORE_ROW,
                appendTail(new Object[] {snapshotDate}, distinct));
    }

    @Override
    public List<RoundEvent> findRoundEvents(String snapshotAtIso) {
        return jdbcTemplate.query(ROUND_EVENTS_SQL, ROUND_EVENT_ROW, snapshotAtIso);
    }

    // ---- 参数装配辅助 ----

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] appendTail(Object[] head, java.util.Collection<Long> tail) {
        Object[] args = new Object[head.length + tail.size()];
        System.arraycopy(head, 0, args, 0, head.length);
        int index = head.length;
        for (Long value : tail) {
            args[index++] = value;
        }
        return args;
    }

    // ---- JSON 解析（FactorSnapshotRepositoryImpl 同款容错口径） ----

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static List<String> codesOf(String json) {
        List<String> codes = new ArrayList<>();
        for (JsonNode element : arrayNodeOf(json)) {
            JsonNode code = element.get("code");
            if (code != null && !code.isNull() && !code.asText().isBlank()) {
                codes.add(code.asText());
            }
        }
        return codes;
    }

    private static List<String> stringsOf(String json) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : arrayNodeOf(json)) {
            if (element.isTextual() && !element.asText().isBlank()) {
                values.add(element.asText());
            }
        }
        return values;
    }

    private static Iterable<JsonNode> arrayNodeOf(String json) {
        try {
            JsonNode node = JSON_MAPPER.readTree(json == null ? "[]" : json);
            return node.isArray() ? node : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
