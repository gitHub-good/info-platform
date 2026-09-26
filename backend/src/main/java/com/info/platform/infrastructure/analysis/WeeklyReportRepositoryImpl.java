package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link WeeklyReportRepository} 端口的 SQLite 实现（M17 T145，V29）。UPSERT {@code ON CONFLICT(week_start)
 * DO UPDATE}（FAILED 重生成整体替换收敛一行，created_at 不覆写）；列表 week_start DESC 游标分页；周窗事件区间取数跨 {@code
 * event_item}（主键归并——一行一主线，重要度排序落 SQL CASE 与日报同构）。
 */
@Repository
public class WeeklyReportRepositoryImpl implements WeeklyReportRepository {

    private static final String UPSERT_SQL =
            """
            INSERT INTO industry_weekly_report
              (week_start, status, content, heat_top, error_message, prompt_version, basis,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(week_start) DO UPDATE SET
              status = excluded.status,
              content = excluded.content,
              heat_top = excluded.heat_top,
              error_message = excluded.error_message,
              prompt_version = excluded.prompt_version,
              basis = excluded.basis,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_BY_WEEK_SQL =
            """
            SELECT id, week_start, status, content, heat_top, error_message, prompt_version,
                   basis, created_at, updated_at
              FROM industry_weekly_report
             WHERE week_start = ?
            """;

    private static final String FIND_PAGE_SQL =
            """
            SELECT id, week_start, status, content, heat_top, error_message, prompt_version,
                   basis, created_at, updated_at
              FROM industry_weekly_report
             WHERE (? IS NULL OR id < ?)
             ORDER BY week_start DESC
             LIMIT ?
            """;

    private static final String FIND_EVENTS_BETWEEN_SQL =
            """
            SELECT e.id, e.news_id, e.event_type, e.summary, e.affected_industries, e.direction,
                   e.importance, e.quote, e.key_figures, e.event_time,
                   ni.url AS news_url, s.name AS source_name
              FROM event_item e
              LEFT JOIN news_item ni ON ni.id = e.news_id
              LEFT JOIN info_source s ON s.id = ni.source_id
             WHERE e.event_date >= ? AND e.event_date <= ?
             ORDER BY CASE e.importance
                        WHEN 'HIGH' THEN 3
                        WHEN 'MEDIUM' THEN 2
                        ELSE 1
                      END DESC, e.id ASC
             LIMIT ?
            """;

    private static final RowMapper<IndustryWeeklyReport> ROW_MAPPER =
            (rs, rowNum) ->
                    IndustryWeeklyReport.reconstruct(
                            rs.getLong("id"),
                            rs.getString("week_start"),
                            ReportStatus.fromName(rs.getString("status")),
                            rs.getString("content"),
                            rs.getString("heat_top"),
                            rs.getString("error_message"),
                            rs.getString("prompt_version"),
                            rs.getString("basis"),
                            parseInstant(rs.getString("created_at")),
                            parseInstant(rs.getString("updated_at")));

    /** 事件行映射（与 DailyReportRepositoryImpl.EVENT_ROW 同构——JSON 列解码降级语义一致）。 */
    private static final RowMapper<DailyReportRepository.ReportEvent> EVENT_ROW =
            (rs, rowNum) ->
                    new DailyReportRepository.ReportEvent(
                            rs.getLong("id"),
                            rs.getLong("news_id"),
                            EventType.fromName(rs.getString("event_type")),
                            rs.getString("summary"),
                            jsonList(rs.getString("affected_industries")),
                            Direction.fromName(rs.getString("direction")),
                            Importance.fromName(rs.getString("importance")),
                            rs.getString("quote"),
                            rs.getString("key_figures"),
                            parseInstant(rs.getString("event_time")),
                            rs.getString("source_name"),
                            rs.getString("news_url"));

    /** JSON 列解码器（无状态共享；回读失败按空表降级）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public WeeklyReportRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IndustryWeeklyReport> findByWeekStart(String weekStart) {
        return jdbcTemplate.query(FIND_BY_WEEK_SQL, ROW_MAPPER, weekStart).stream().findFirst();
    }

    @Override
    public List<IndustryWeeklyReport> findPage(Long beforeId, int limit) {
        return jdbcTemplate.query(FIND_PAGE_SQL, ROW_MAPPER, beforeId, beforeId, limit);
    }

    @Override
    public IndustryWeeklyReport upsert(IndustryWeeklyReport report) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(
                                    UPSERT_SQL, java.sql.Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, report.getWeekStart());
                    ps.setString(2, report.getStatus().name());
                    ps.setString(3, report.getContent());
                    ps.setString(4, report.getHeatTop());
                    ps.setString(5, report.getErrorMessage());
                    ps.setString(6, report.getPromptVersion());
                    ps.setString(7, report.getBasis());
                    ps.setString(
                            8, isoOf(report.getCreatedAt() == null ? now : report.getCreatedAt()));
                    ps.setString(9, isoOf(now));
                    return ps;
                });
        return findByWeekStart(report.getWeekStart())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "周报 UPSERT 后回读失败: " + report.getWeekStart()));
    }

    @Override
    public List<DailyReportRepository.ReportEvent> findEventsBetween(
            String eventDateFrom, String eventDateTo, int cap) {
        return jdbcTemplate.query(
                FIND_EVENTS_BETWEEN_SQL, EVENT_ROW, eventDateFrom, eventDateTo, cap);
    }

    private static List<String> jsonList(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of(); // JSON 列损坏按空表降级（历史行不阻断周窗聚合）
        }
    }

    private static String isoOf(Instant instant) {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
