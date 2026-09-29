package com.info.platform.infrastructure.newspulse;

import com.info.platform.domain.newspulse.NewsPulseRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link NewsPulseRepository} 端口的 SQLite/JdbcTemplate 实现（V3.2 M28，V36 表）。
 *
 * <p>窗口条目按 news_item.fetched_at（入库时刻）圈窗——资讯「新发生」口径取入库面（published_at 跨源时区杂乱，
 * 且脉搏分析的对象是「平台新抓到的信息流」）；时间戳列均 ISO-8601 文本（UTC 整秒），与全库惯例一致。
 */
@Repository
public class NewsPulseRepositoryImpl implements NewsPulseRepository {

    private static final String ITEM_COLUMNS =
            """
            na.news_id, ni.title, na.main_category, na.confidence,
            na.importance_score, na.matched_subjects
            """;

    private static final RowMapper<WindowItem> ITEM_ROW =
            (rs, rowNum) ->
                    new WindowItem(
                            rs.getLong("news_id"),
                            rs.getString("title"),
                            rs.getString("main_category"),
                            (Double) rs.getObject("confidence"),
                            rs.getDouble("importance_score"),
                            rs.getString("matched_subjects"));

    private static final String PULSE_COLUMNS =
            """
            id, window_key, window_start, window_end, news_count, classified_count,
            industry_stats, market_stats, analysis, model, prompt_version,
            trigger_source, degraded, degraded_reason, created_at
            """;

    private static final RowMapper<PulseRow> PULSE_ROW =
            (rs, rowNum) ->
                    new PulseRow(
                            rs.getLong("id"),
                            rs.getString("window_key"),
                            rs.getString("window_start"),
                            rs.getString("window_end"),
                            rs.getInt("news_count"),
                            rs.getInt("classified_count"),
                            rs.getString("industry_stats"),
                            rs.getString("market_stats"),
                            rs.getString("analysis"),
                            rs.getString("model"),
                            rs.getString("prompt_version"),
                            rs.getString("trigger_source"),
                            rs.getInt("degraded") == 1,
                            rs.getString("degraded_reason"),
                            rs.getString("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public NewsPulseRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<WindowItem> findWindowItems(String startIso, String endIso, int cap) {
        // ISO 文本比较 = UTC 时刻比较（同构整秒文本，字典序即时间序）；importance 降序 + news_id 降序稳定决胜
        return jdbcTemplate.query(
                """
                SELECT %s
                  FROM news_analysis na
                  JOIN news_item ni ON ni.id = na.news_id
                 WHERE na.l0_result = 'PASS'
                   AND ni.fetched_at >= ? AND ni.fetched_at < ?
                 ORDER BY na.importance_score DESC, na.news_id DESC
                 LIMIT ?
                """
                        .formatted(ITEM_COLUMNS),
                ITEM_ROW,
                startIso,
                endIso,
                cap);
    }

    @Override
    public long countWindowItems(String startIso, String endIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM news_analysis na
                          JOIN news_item ni ON ni.id = na.news_id
                         WHERE na.l0_result = 'PASS'
                           AND ni.fetched_at >= ? AND ni.fetched_at < ?
                        """,
                        Long.class,
                        startIso,
                        endIso);
        return count == null ? 0L : count;
    }

    @Override
    public long countWindowClassified(String startIso, String endIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM news_analysis na
                          JOIN news_item ni ON ni.id = na.news_id
                         WHERE na.l0_result = 'PASS' AND na.l1_status = 'DONE'
                           AND ni.fetched_at >= ? AND ni.fetched_at < ?
                        """,
                        Long.class,
                        startIso,
                        endIso);
        return count == null ? 0L : count;
    }

    @Override
    public Optional<PulseRow> findLatest(String windowKey) {
        List<PulseRow> rows =
                jdbcTemplate.query(
                        "SELECT %s FROM news_pulse_analysis WHERE window_key = ?"
                                        .formatted(PULSE_COLUMNS)
                                + " ORDER BY id DESC LIMIT 1",
                        PULSE_ROW,
                        windowKey);
        return rows.stream().findFirst();
    }

    @Override
    public List<PulseRow> findLatestEachWindow() {
        return jdbcTemplate.query(
                """
                SELECT %s FROM news_pulse_analysis p
                 WHERE id = (SELECT MAX(id) FROM news_pulse_analysis
                              WHERE window_key = p.window_key)
                 ORDER BY window_key
                """
                        .formatted(
                                "p.id, p.window_key, p.window_start, p.window_end, p.news_count,"
                                        + " p.classified_count, p.industry_stats, p.market_stats,"
                                        + " p.analysis, p.model, p.prompt_version,"
                                        + " p.trigger_source, p.degraded, p.degraded_reason,"
                                        + " p.created_at"),
                PULSE_ROW);
    }

    @Override
    public PulseRow insert(PulseRow row) {
        Timestamp nowTs = Timestamp.from(Instant.now());
        String now = nowTs.toInstant().toString();
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(
                con -> {
                    var ps =
                            con.prepareStatement(
                                    """
                                    INSERT INTO news_pulse_analysis
                                      (window_key, window_start, window_end, news_count,
                                       classified_count, industry_stats, market_stats, analysis,
                                       model, prompt_version, trigger_source, degraded,
                                       degraded_reason, created_at, updated_at)
                                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """);
                    ps.setString(1, row.windowKey());
                    ps.setString(2, row.windowStart());
                    ps.setString(3, row.windowEnd());
                    ps.setInt(4, row.newsCount());
                    ps.setInt(5, row.classifiedCount());
                    ps.setString(6, row.industryStats());
                    ps.setString(7, row.marketStats());
                    ps.setString(8, row.analysis());
                    ps.setString(9, row.model());
                    ps.setString(10, row.promptVersion());
                    ps.setString(11, row.triggerSource());
                    ps.setInt(12, row.degraded() ? 1 : 0);
                    ps.setString(13, row.degradedReason());
                    ps.setString(14, now);
                    ps.setString(15, now);
                    return ps;
                },
                keyHolder);
        Number key = keyHolder.getKey();
        return new PulseRow(
                key == null ? null : key.longValue(),
                row.windowKey(),
                row.windowStart(),
                row.windowEnd(),
                row.newsCount(),
                row.classifiedCount(),
                row.industryStats(),
                row.marketStats(),
                row.analysis(),
                row.model(),
                row.promptVersion(),
                row.triggerSource(),
                row.degraded(),
                row.degradedReason(),
                now);
    }
}
