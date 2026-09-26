package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link HeatSnapshotRepository} 端口的 SQLite 实现（M15 T123，ADR-0046 裁决 1）。
 *
 * <p>UPSERT {@code ON CONFLICT(industry, window_type) DO UPDATE}（62 行常驻当前值，重跑收敛）；窗口现算与下钻 join
 * {@code news_analysis/news_item/event_item}（独立表代价 = 一次 join，24h 窗 ≤700 行毫秒级——裁决 1 论证）；事件行业匹配走 JSON
 * 文本包含（引号定界 {@code '%"银行"%'}——枚举名不含引号/百分号，无转义面，且不受「非银金融」等子串误配）。下钻 events 清单的
 * info_source 取 LEFT JOIN（软删源行不消失，sourceName 置空——行数与 countIndustryEventItems 对账保持相等）。
 */
@Repository
public class HeatSnapshotRepositoryImpl implements HeatSnapshotRepository {

    private static final String UPSERT_SQL =
            """
            INSERT INTO industry_heat_snapshot
              (industry, window_type, heat_score, prev_score, delta_pct, news_count, event_count,
               basis, snapshot_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(industry, window_type) DO UPDATE SET
              heat_score = excluded.heat_score,
              prev_score = excluded.prev_score,
              delta_pct = excluded.delta_pct,
              news_count = excluded.news_count,
              event_count = excluded.event_count,
              basis = excluded.basis,
              snapshot_at = excluded.snapshot_at,
              updated_at = excluded.updated_at
            """;

    private static final String FIND_BOARD_SQL =
            """
            SELECT id, industry, window_type, heat_score, prev_score, delta_pct, news_count,
                   event_count, basis, snapshot_at, created_at, updated_at
              FROM industry_heat_snapshot
             WHERE window_type = ?
             ORDER BY heat_score DESC, news_count DESC, industry ASC
            """;

    private static final String FIND_WINDOW_ITEMS_SQL =
            """
            SELECT na.main_category, ni.published_at, e.importance AS event_importance,
                   e.affected_industries
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
              LEFT JOIN event_item e ON e.news_id = na.news_id
             WHERE na.l0_result = 'PASS'
               AND na.l1_status = 'DONE'
               AND ni.published_at >= ? AND ni.published_at < ?
             ORDER BY ni.published_at ASC, ni.id ASC
            """;

    private static final String FIND_INDUSTRY_NEWS_SQL =
            """
            SELECT na.news_id, ni.title, s.name AS source_name, ni.published_at,
                   CASE WHEN e.id IS NULL THEN 0 ELSE 1 END AS has_event, ni.url
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
              JOIN info_source s ON s.id = ni.source_id
              LEFT JOIN event_item e ON e.news_id = na.news_id
             WHERE na.l0_result = 'PASS'
               AND na.l1_status = 'DONE'
               AND na.main_category = ?
               AND ni.published_at >= ? AND ni.published_at < ?
            """;

    private static final String FIND_INDUSTRY_EVENTS_SQL =
            """
            SELECT e.id AS event_id, e.news_id, ni.title AS news_title, e.event_type, e.summary,
                   e.direction, e.importance, e.event_time, ni.url AS news_url, e.quote,
                   s.name AS source_name
              FROM event_item e
              JOIN news_item ni ON ni.id = e.news_id
              LEFT JOIN info_source s ON s.id = ni.source_id
             WHERE e.affected_industries LIKE ?
               AND ni.published_at >= ? AND ni.published_at < ?
            """;

    private static final RowMapper<IndustryHeatSnapshot> SNAPSHOT_ROW =
            (rs, rowNum) ->
                    IndustryHeatSnapshot.reconstruct(
                            rs.getLong("id"),
                            rs.getString("industry"),
                            HeatWindow.fromName(rs.getString("window_type")),
                            rs.getDouble("heat_score"),
                            rs.getDouble("prev_score"),
                            rs.getDouble("delta_pct"),
                            rs.getLong("news_count"),
                            rs.getLong("event_count"),
                            rs.getString("basis"),
                            Instant.parse(rs.getString("snapshot_at")),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at")));

    private static final RowMapper<HeatSnapshotRepository.WindowItem> WINDOW_ITEM_ROW =
            (rs, rowNum) -> {
                String importanceText = rs.getString("event_importance");
                return new HeatSnapshotRepository.WindowItem(
                        rs.getString("main_category"),
                        Instant.parse(rs.getString("published_at")),
                        importanceText == null ? null : Importance.fromName(importanceText),
                        parseIndustries(rs.getString("affected_industries")));
            };

    private static final RowMapper<HeatSnapshotRepository.IndustryNewsItem> NEWS_ITEM_ROW =
            (rs, rowNum) ->
                    new HeatSnapshotRepository.IndustryNewsItem(
                            rs.getLong("news_id"),
                            rs.getString("title"),
                            rs.getString("source_name"),
                            Instant.parse(rs.getString("published_at")),
                            rs.getInt("has_event") == 1,
                            rs.getString("url"));

    private static final RowMapper<HeatSnapshotRepository.IndustryEventItem> EVENT_ITEM_ROW =
            (rs, rowNum) ->
                    new HeatSnapshotRepository.IndustryEventItem(
                            rs.getLong("event_id"),
                            rs.getLong("news_id"),
                            rs.getString("news_title"),
                            EventType.fromName(rs.getString("event_type")),
                            rs.getString("summary"),
                            Direction.fromName(rs.getString("direction")),
                            Importance.fromName(rs.getString("importance")),
                            Instant.parse(rs.getString("event_time")),
                            rs.getString("news_url"),
                            rs.getString("quote"),
                            rs.getString("source_name"));

    private final JdbcTemplate jdbcTemplate;

    public HeatSnapshotRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<IndustryHeatSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int[] results =
                jdbcTemplate.batchUpdate(
                        UPSERT_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                IndustryHeatSnapshot row = snapshots.get(i);
                                ps.setString(1, row.getIndustry());
                                ps.setString(2, row.getWindow().name());
                                ps.setDouble(3, row.getHeatScore());
                                ps.setDouble(4, row.getPrevScore());
                                ps.setDouble(5, row.getDeltaPct());
                                ps.setLong(6, row.getNewsCount());
                                ps.setLong(7, row.getEventCount());
                                ps.setString(8, row.getBasis());
                                ps.setString(9, row.getSnapshotAt().toString());
                                ps.setString(10, now);
                                ps.setString(11, now);
                            }

                            @Override
                            public int getBatchSize() {
                                return snapshots.size();
                            }
                        });
        int upserted = 0;
        for (int result : results) {
            upserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return upserted;
    }

    @Override
    public List<IndustryHeatSnapshot> findBoard(HeatWindow window) {
        return jdbcTemplate.query(FIND_BOARD_SQL, SNAPSHOT_ROW, window.name());
    }

    @Override
    public List<HeatSnapshotRepository.WindowItem> findWindowItems(
            String publishedFromIso, String publishedToIso) {
        return jdbcTemplate.query(
                FIND_WINDOW_ITEMS_SQL, WINDOW_ITEM_ROW, publishedFromIso, publishedToIso);
    }

    @Override
    public List<HeatSnapshotRepository.IndustryNewsItem> findIndustryNewsItems(
            String industry,
            String publishedFromIso,
            String publishedToIso,
            Long beforeNewsId,
            int limit) {
        StringBuilder sql = new StringBuilder(FIND_INDUSTRY_NEWS_SQL);
        List<Object> args = new ArrayList<>();
        args.add(industry);
        args.add(publishedFromIso);
        args.add(publishedToIso);
        appendCursor(sql, args, beforeNewsId, "na.news_id");
        sql.append(" ORDER BY na.news_id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), NEWS_ITEM_ROW, args.toArray());
    }

    @Override
    public long countIndustryNewsItems(
            String industry, String publishedFromIso, String publishedToIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_analysis na JOIN news_item ni ON ni.id = na.news_id"
                                + " WHERE na.l0_result = 'PASS' AND na.l1_status = 'DONE'"
                                + " AND na.main_category = ? AND ni.published_at >= ? AND ni.published_at < ?",
                        Long.class,
                        industry,
                        publishedFromIso,
                        publishedToIso);
        return count == null ? 0 : count;
    }

    @Override
    public List<HeatSnapshotRepository.IndustryEventItem> findIndustryEventItems(
            String industry,
            String publishedFromIso,
            String publishedToIso,
            Long beforeEventId,
            int limit) {
        StringBuilder sql = new StringBuilder(FIND_INDUSTRY_EVENTS_SQL);
        List<Object> args = new ArrayList<>();
        args.add(quotedContains(industry));
        args.add(publishedFromIso);
        args.add(publishedToIso);
        appendCursor(sql, args, beforeEventId, "e.id");
        sql.append(" ORDER BY e.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), EVENT_ITEM_ROW, args.toArray());
    }

    @Override
    public long countIndustryEventItems(
            String industry, String publishedFromIso, String publishedToIso) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_item e JOIN news_item ni ON ni.id = e.news_id"
                                + " WHERE e.affected_industries LIKE ?"
                                + " AND ni.published_at >= ? AND ni.published_at < ?",
                        Long.class,
                        quotedContains(industry),
                        publishedFromIso,
                        publishedToIso);
        return count == null ? 0 : count;
    }

    /** JSON 数组元素包含匹配（{"枚举"} 引号定界——不受「非银金融」等子串误配）。 */
    private static String quotedContains(String industry) {
        return "%\"" + industry + "\"%";
    }

    private static void appendCursor(
            StringBuilder sql, List<Object> args, Long beforeId, String column) {
        if (beforeId == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" < ?");
        args.add(beforeId);
    }

    /** affected_industries JSON 数组解析（损坏容错空表——留痕列非权威面；SourceConfigCodec 静态 MAPPER 先例）。 */
    private static final ObjectMapper INDUSTRIES_MAPPER = new ObjectMapper();

    private static List<String> parseIndustries(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return INDUSTRIES_MAPPER.readValue(
                    json,
                    INDUSTRIES_MAPPER
                            .getTypeFactory()
                            .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
