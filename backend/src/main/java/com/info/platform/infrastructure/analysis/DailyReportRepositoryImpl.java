package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.ReportStatus;
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
 * {@link DailyReportRepository} 端口的 SQLite 实现（M15 T124，ADR-0046 裁决 1）。
 *
 * <p>UPSERT {@code ON CONFLICT(report_date) DO UPDATE}（FAILED 重生成整体替换收敛为一行，created_at 不覆写）；列表
 * report_date DESC 游标分页（每日一行单调，id 游标等价）；统计注入两查询跨 {@code news_analysis/news_item} join 与 {@code
 * event_item} 日窗（量级 ≤700 行毫秒级——裁决 1 论证）；事件重要度排序落 SQL CASE（白名单枚举三值，无注入面）。
 */
@Repository
public class DailyReportRepositoryImpl implements DailyReportRepository {

    private static final Logger log = LoggerFactory.getLogger(DailyReportRepositoryImpl.class);

    private static final String UPSERT_SQL =
            """
            INSERT INTO industry_daily_report
              (report_date, status, content, heat_top, error_message, prompt_version, basis,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(report_date) DO UPDATE SET
              status = excluded.status,
              content = excluded.content,
              heat_top = excluded.heat_top,
              error_message = excluded.error_message,
              prompt_version = excluded.prompt_version,
              basis = excluded.basis,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_BY_DATE_SQL =
            """
            SELECT id, report_date, status, content, heat_top, error_message, prompt_version,
                   basis, created_at, updated_at
              FROM industry_daily_report
             WHERE report_date = ?
            """;

    private static final String FIND_PAGE_SQL =
            """
            SELECT id, report_date, status, content, heat_top, error_message, prompt_version,
                   basis, created_at, updated_at
              FROM industry_daily_report
             WHERE (? IS NULL OR id < ?)
             ORDER BY report_date DESC
             LIMIT ?
            """;

    private static final String COUNT_NEWS_SQL =
            """
            SELECT na.main_category AS category, COUNT(*) AS news_count
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
             WHERE na.l0_result = 'PASS'
               AND na.l1_status = 'DONE'
               AND ni.published_at >= ? AND ni.published_at < ?
             GROUP BY na.main_category
             ORDER BY news_count DESC, category ASC
            """;

    private static final String FIND_EVENTS_SQL =
            """
            SELECT e.id, e.news_id, e.event_type, e.summary, e.affected_industries, e.direction,
                   e.importance, e.quote, e.key_figures, e.event_time,
                   ni.url AS news_url, s.name AS source_name
              FROM event_item e
              LEFT JOIN news_item ni ON ni.id = e.news_id
              LEFT JOIN info_source s ON s.id = ni.source_id
             WHERE e.event_date = ?
             ORDER BY CASE e.importance
                        WHEN 'HIGH' THEN 3
                        WHEN 'MEDIUM' THEN 2
                        ELSE 1
                      END DESC, e.id ASC
             LIMIT ?
            """;

    private static final RowMapper<IndustryDailyReport> REPORT_ROW =
            (rs, rowNum) ->
                    IndustryDailyReport.reconstruct(
                            rs.getLong("id"),
                            rs.getString("report_date"),
                            ReportStatus.fromName(rs.getString("status")),
                            rs.getString("content"),
                            rs.getString("heat_top"),
                            rs.getString("error_message"),
                            rs.getString("prompt_version"),
                            rs.getString("basis"),
                            nullableInstant(rs.getString("created_at")),
                            nullableInstant(rs.getString("updated_at")));

    private static final RowMapper<IndustryCount> COUNT_ROW =
            (rs, rowNum) -> new IndustryCount(rs.getString("category"), rs.getLong("news_count"));

    private static final RowMapper<ReportEvent> EVENT_ROW =
            (rs, rowNum) ->
                    new ReportEvent(
                            rs.getLong("id"),
                            rs.getLong("news_id"),
                            EventType.fromName(rs.getString("event_type")),
                            rs.getString("summary"),
                            jsonList(rs.getString("affected_industries")),
                            Direction.fromName(rs.getString("direction")),
                            Importance.fromName(rs.getString("importance")),
                            rs.getString("quote"),
                            rs.getString("key_figures"),
                            nullableInstant(rs.getString("event_time")),
                            rs.getString("source_name"),
                            rs.getString("news_url"));

    /** RowMapper 静态上下文共享解码器（无状态；回读失败按空表降级——历史行损坏不阻断读）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public DailyReportRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IndustryDailyReport> findByReportDate(String reportDate) {
        return jdbcTemplate.query(FIND_BY_DATE_SQL, REPORT_ROW, reportDate).stream().findFirst();
    }

    @Override
    public List<IndustryDailyReport> findPage(Long beforeId, int limit) {
        return jdbcTemplate.query(
                FIND_PAGE_SQL,
                ps -> {
                    ps.setObject(1, beforeId);
                    ps.setObject(2, beforeId);
                    ps.setInt(3, limit);
                },
                REPORT_ROW);
    }

    @Override
    public IndustryDailyReport upsert(IndustryDailyReport report) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(UPSERT_SQL);
                    ps.setString(1, report.getReportDate());
                    ps.setString(2, report.getStatus().name());
                    ps.setString(3, report.getContent());
                    ps.setString(4, report.getHeatTop());
                    ps.setString(5, report.getErrorMessage());
                    ps.setString(6, report.getPromptVersion());
                    ps.setString(7, report.getBasis());
                    ps.setString(8, now);
                    ps.setString(9, now);
                    return ps;
                });
        return findByReportDate(report.getReportDate())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "industry_daily_report 回读失败: " + report.getReportDate()));
    }

    @Override
    public List<IndustryCount> countNewsByIndustry(String publishedFromIso, String publishedToIso) {
        return jdbcTemplate.query(COUNT_NEWS_SQL, COUNT_ROW, publishedFromIso, publishedToIso);
    }

    @Override
    public List<ReportEvent> findEventsByDate(String eventDate, int cap) {
        return jdbcTemplate.query(FIND_EVENTS_SQL, EVENT_ROW, eventDate, cap);
    }

    private static List<String> jsonList(String json) {
        try {
            return json == null ? List.of() : MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("event_item.affected_industries 解析失败（按空表降级）: {}", e.getMessage());
            return List.of();
        }
    }

    private static Instant nullableInstant(String iso) {
        return iso == null || iso.isBlank() ? null : Instant.parse(iso);
    }
}
