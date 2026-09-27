package com.info.platform.infrastructure.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.ValuationEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link FactorSnapshotRepository} 端口的 SQLite 实现（M20 T170，V30 表）。
 *
 * <p>UPSERT {@code ON CONFLICT(subject_id, snapshot_date) DO UPDATE}（当日重跑同键覆盖——幂等最后防线，方案库 03）；
 * 输入投影：事件窗（subjects/affected JSON 解析为代码/行业数组，损坏容错空表）、资讯回联窗（DONE + published_at ISO 界，上海日转
 * LocalDate）、H24 热度行；coverage 计数与排名计数（countScoreGreaterThan 严格大于——并列同名次）； data_flags 计数走 JSON 引号定界
 * LIKE（HeatSnapshotRepositoryImpl 同款，枚举名不含引号/百分号无转义面）。
 */
@Repository
public class FactorSnapshotRepositoryImpl implements FactorSnapshotRepository {

    /** 快照口径时区（published_at ISO → 上海日）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final String UPSERT_SQL =
            """
            INSERT INTO subject_factor_snapshot
              (subject_id, snapshot_date, f_catalyst, f_conduction, f_fundamental, f_risk,
               f_valuation, total_score, breakthrough, factor_detail, data_flags, weight_basis,
               computed_at, last_event_date, increment_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(subject_id, snapshot_date) DO UPDATE SET
              f_catalyst = excluded.f_catalyst,
              f_conduction = excluded.f_conduction,
              f_fundamental = excluded.f_fundamental,
              f_risk = excluded.f_risk,
              f_valuation = excluded.f_valuation,
              total_score = excluded.total_score,
              breakthrough = excluded.breakthrough,
              factor_detail = excluded.factor_detail,
              data_flags = excluded.data_flags,
              weight_basis = excluded.weight_basis,
              computed_at = excluded.computed_at,
              last_event_date = excluded.last_event_date,
              increment_at = excluded.increment_at,
              updated_at = excluded.updated_at
            """;

    private static final String ACTIVE_SUBJECTS_SQL =
            """
            SELECT id, subject_code, name FROM subject_master
             WHERE market = 'A_SHARE' AND status = 1
             ORDER BY id ASC
            """;

    private static final String EVENTS_WINDOW_SQL =
            """
            SELECT id, summary, event_date, direction, importance, event_type, subjects,
                   affected_industries
              FROM event_item
             WHERE event_date >= ? AND event_date <= ?
             ORDER BY id ASC
            """;

    private static final String MATCHED_NEWS_SQL =
            """
            SELECT na.matched_subjects, na.main_category, na.sub_industry, ni.published_at
              FROM news_analysis na
              JOIN news_item ni ON ni.id = na.news_id
             WHERE na.l1_status = 'DONE'
               AND na.matched_subjects IS NOT NULL
               AND ni.published_at >= ? AND ni.published_at < ?
             ORDER BY ni.published_at ASC, na.news_id ASC
            """;

    private static final String H24_HEAT_SQL =
            """
            SELECT industry, heat_score FROM industry_heat_snapshot
             WHERE window_type = ?
            """;

    /** 行业成员投影（M21 T180 六输入）：industry 非空的 A 股启用行（路 C 映射原料，id 升序确定性）。 */
    private static final String INDUSTRY_MEMBERS_SQL =
            """
            SELECT subject_code, industry FROM subject_master
             WHERE market = 'A_SHARE' AND status = 1 AND industry IS NOT NULL AND TRIM(industry) <> ''
             ORDER BY id ASC
            """;

    /** 粗筛/榜单池行投影（M21 T183）：快照行 join 标的名录（板块原文随行携带，SW 映射在读侧）。 */
    private static final String POOL_ROWS_SQL =
            """
            SELECT s.subject_id, m.subject_code, m.name, m.industry,
                   s.f_catalyst, s.f_conduction, s.f_fundamental, s.f_risk, s.f_valuation,
                   s.total_score, s.breakthrough, s.factor_detail, s.weight_basis, s.last_event_date
              FROM subject_factor_snapshot s
              JOIN subject_master m ON m.id = s.subject_id
             WHERE s.snapshot_date = ?
             ORDER BY s.subject_id ASC
            """;

    private static final RowMapper<SubjectRef> SUBJECT_ROW =
            (rs, rowNum) ->
                    new SubjectRef(
                            rs.getLong("id"), rs.getString("subject_code"), rs.getString("name"));

    private static final RowMapper<EventRef> EVENT_ROW =
            (rs, rowNum) ->
                    new EventRef(
                            new ValuationEvent(
                                    rs.getLong("id"),
                                    rs.getString("summary"),
                                    LocalDate.parse(rs.getString("event_date")),
                                    Direction.fromName(rs.getString("direction")),
                                    Importance.fromName(rs.getString("importance")),
                                    EventType.fromName(rs.getString("event_type"))),
                            codesOf(rs.getString("subjects")),
                            stringsOf(rs.getString("affected_industries")));

    private static final RowMapper<NewsLinkRow> NEWS_ROW =
            (rs, rowNum) ->
                    new NewsLinkRow(
                            codesOf(rs.getString("matched_subjects")),
                            rs.getString("main_category"),
                            rs.getString("sub_industry"),
                            Instant.parse(rs.getString("published_at"))
                                    .atZone(SHANGHAI)
                                    .toLocalDate());

    private static final RowMapper<HeatRow> HEAT_ROW =
            (rs, rowNum) -> new HeatRow(rs.getString("industry"), rs.getDouble("heat_score"));

    private static final RowMapper<IndustryMemberRow> INDUSTRY_MEMBER_ROW =
            (rs, rowNum) ->
                    new IndustryMemberRow(rs.getString("subject_code"), rs.getString("industry"));

    private static final RowMapper<PoolRow> POOL_ROW =
            (rs, rowNum) ->
                    new PoolRow(
                            rs.getLong("subject_id"),
                            rs.getString("subject_code"),
                            rs.getString("name"),
                            rs.getString("industry"),
                            rs.getDouble("f_catalyst"),
                            rs.getDouble("f_conduction"),
                            rs.getDouble("f_fundamental"),
                            rs.getDouble("f_risk"),
                            rs.getDouble("f_valuation"),
                            rs.getDouble("total_score"),
                            rs.getInt("breakthrough") == 1,
                            rs.getString("factor_detail"),
                            rs.getString("weight_basis"),
                            rs.getString("last_event_date"));

    private static final RowMapper<FactorSnapshotRow> SNAPSHOT_ROW = snapshotRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public FactorSnapshotRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<FactorSnapshotRow> rows) {
        return upsertWithIncrementAt(rows, null);
    }

    /** M22 T190：增量覆盖（increment_at 置本轮时刻）——同键覆盖幂等与全量共用同一 UPSERT 语句。 */
    @Override
    public int upsertAllIncremental(List<FactorSnapshotRow> rows, String incrementAtIso) {
        return upsertWithIncrementAt(
                rows, incrementAtIso == null || incrementAtIso.isBlank() ? null : incrementAtIso);
    }

    private int upsertWithIncrementAt(List<FactorSnapshotRow> rows, String incrementAtIso) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        int[] results =
                jdbcTemplate.batchUpdate(
                        UPSERT_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                FactorSnapshotRow row = rows.get(i);
                                ps.setLong(1, row.subjectId());
                                ps.setString(2, row.snapshotDate());
                                ps.setDouble(3, row.fCatalyst());
                                ps.setDouble(4, row.fConduction());
                                ps.setDouble(5, row.fFundamental());
                                ps.setDouble(6, row.fRisk());
                                ps.setDouble(7, row.fValuation());
                                ps.setDouble(8, row.totalScore());
                                ps.setInt(9, row.breakthrough() ? 1 : 0);
                                ps.setString(10, row.factorDetailJson());
                                ps.setString(11, row.dataFlagsJson());
                                ps.setString(12, row.weightBasis());
                                ps.setString(13, row.computedAtIso());
                                ps.setString(14, row.lastEventDate());
                                ps.setString(15, incrementAtIso);
                                ps.setString(16, now);
                                ps.setString(17, now);
                            }

                            @Override
                            public int getBatchSize() {
                                return rows.size();
                            }
                        });
        int upserted = 0;
        for (int result : results) {
            upserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return upserted;
    }

    @Override
    public List<SubjectRef> findActiveSubjects() {
        return jdbcTemplate.query(ACTIVE_SUBJECTS_SQL, SUBJECT_ROW);
    }

    @Override
    public long countActiveSubjects() {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_master WHERE market = 'A_SHARE' AND status = 1",
                        Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<EventRef> findEventsInWindow(String fromDate, String toDate) {
        return jdbcTemplate.query(EVENTS_WINDOW_SQL, EVENT_ROW, fromDate, toDate);
    }

    @Override
    public List<NewsLinkRow> findMatchedNewsInWindow(String fromIso, String toIso) {
        return jdbcTemplate.query(MATCHED_NEWS_SQL, NEWS_ROW, fromIso, toIso);
    }

    @Override
    public List<HeatRow> findH24Heat() {
        return jdbcTemplate.query(H24_HEAT_SQL, HEAT_ROW, HeatWindow.H24.name());
    }

    @Override
    public List<IndustryMemberRow> findIndustryMembers() {
        return jdbcTemplate.query(INDUSTRY_MEMBERS_SQL, INDUSTRY_MEMBER_ROW);
    }

    @Override
    public List<PoolRow> findPoolRowsByDate(String snapshotDate) {
        return jdbcTemplate.query(POOL_ROWS_SQL, POOL_ROW, snapshotDate);
    }

    @Override
    public Optional<String> findLatestSnapshotDate() {
        List<String> dates =
                jdbcTemplate.queryForList(
                        "SELECT MAX(snapshot_date) FROM subject_factor_snapshot", String.class);
        return dates.isEmpty() || dates.get(0) == null
                ? Optional.empty()
                : Optional.of(dates.get(0));
    }

    @Override
    public long countByDate(String snapshotDate) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_factor_snapshot WHERE snapshot_date = ?",
                        Long.class,
                        snapshotDate);
        return count == null ? 0 : count;
    }

    @Override
    public long countScoreGreaterThan(String snapshotDate, double score) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_factor_snapshot"
                                + " WHERE snapshot_date = ? AND total_score > ?",
                        Long.class,
                        snapshotDate,
                        score);
        return count == null ? 0 : count;
    }

    @Override
    public long countFlagged(String snapshotDate, String flag) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_factor_snapshot"
                                + " WHERE snapshot_date = ? AND data_flags LIKE ?",
                        Long.class,
                        snapshotDate,
                        "%\"" + flag + "\"%");
        return count == null ? 0 : count;
    }

    @Override
    public Optional<FactorSnapshotRow> findLatestBySubject(long subjectId) {
        List<FactorSnapshotRow> rows =
                jdbcTemplate.query(
                        "SELECT id, subject_id, snapshot_date, f_catalyst, f_conduction,"
                                + " f_fundamental, f_risk, f_valuation, total_score,"
                                + " breakthrough, factor_detail, data_flags, weight_basis,"
                                + " computed_at, last_event_date FROM subject_factor_snapshot"
                                + " WHERE subject_id = ? ORDER BY snapshot_date DESC LIMIT 1",
                        SNAPSHOT_ROW,
                        subjectId);
        return rows.stream().findFirst();
    }

    private static RowMapper<FactorSnapshotRow> snapshotRowMapper() {
        return (rs, rowNum) ->
                new FactorSnapshotRow(
                        rs.getLong("subject_id"),
                        rs.getString("snapshot_date"),
                        rs.getDouble("f_catalyst"),
                        rs.getDouble("f_conduction"),
                        rs.getDouble("f_fundamental"),
                        rs.getDouble("f_risk"),
                        rs.getDouble("f_valuation"),
                        rs.getDouble("total_score"),
                        rs.getInt("breakthrough") == 1,
                        rs.getString("factor_detail"),
                        rs.getString("data_flags"),
                        rs.getString("weight_basis"),
                        rs.getString("computed_at"),
                        rs.getString("last_event_date"));
    }

    /** JSON 解析静态映射器（静态 RowMapper 可达；SourceConfigCodec 静态 MAPPER 先例）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** subjects/matched_subjects JSON（[{code,name,industry}]）→ code 数组（损坏容错空表——回联列非权威面）。 */
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

    /** 字符串数组 JSON（affected_industries）→ List（损坏容错空表）。 */
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
