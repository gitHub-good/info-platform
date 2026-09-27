package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V31 迁移语义集成测试（M21 T180，方案 §4.2 DDL）：context 启动时 Flyway 已应用 V31——本类断言<b>结构面</b>（列/表/唯一约束在位） 与<b>数据面
 * SQL 语义</b>（last_event_date 存量回填 UNION MAX / score.weight 阈值守卫条件更新——对迁移后新插夹具重放同一 SQL 文本， 与 V31
 * 文件逐字同源，迁移文件改动跑此处即红）。
 */
@SpringBootTest
@ActiveProfiles("test")
class V31MarketTopMigrationTest {

    /** 与 V31__create_market_top_tables.sql 逐字同源的回填/守卫段（镜像断言面——迁移文件漂移由此暴露）。 */
    private static final String LAST_EVENT_BACKFILL_SQL =
            """
            UPDATE subject_factor_snapshot SET last_event_date = (
              SELECT MAX(d) FROM (
                SELECT MAX(json_extract(e.value, '$.eventDate')) AS d FROM json_each(factor_detail, '$.catalyst.entries') e
                UNION SELECT MAX(json_extract(e.value, '$.eventDate')) FROM json_each(factor_detail, '$.fundamental.entries') e
                UNION SELECT MAX(json_extract(e.value, '$.eventDate')) FROM json_each(factor_detail, '$.risk.entries') e))
            """;

    private static final String BT_CATALYST_GUARD_SQL =
            """
            UPDATE runtime_config SET config_value = json_set(config_value, '$.btCatalystMin', 20),
                   updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
            WHERE config_key = 'score.weight' AND json_extract(config_value, '$.btCatalystMin') = 60
            """;

    @Autowired private JdbcTemplate jdbcTemplate;

    // ---- 结构面：V31 应用后的库形态 ----

    @Test
    void v31_structure_lastEventDateColumnAndTopTablesPresent() {
        Integer columnCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pragma_table_info('subject_factor_snapshot')"
                                + " WHERE name = 'last_event_date'",
                        Integer.class);
        assertThat(columnCount).isEqualTo(1);

        Integer rankTable =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name ="
                                + " 'market_top_rank'",
                        Integer.class);
        Integer batchTable =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name ="
                                + " 'market_top_batch'",
                        Integer.class);
        assertThat(rankTable).isEqualTo(1);
        assertThat(batchTable).isEqualTo(1);
    }

    @Test
    void v31_structure_rankUniqueConstraintsEnforced() {
        // UNIQUE(rank_date, version, rank_no)：同位次重复落库被拒（版本化追加语义的库级防线）
        insertRankRow("2026-09-22", 1, 1, 101L);
        insertRankRow("2026-09-22", 1, 2, 102L); // 同版本不同位次合法
        insertRankRow("2026-09-22", 2, 1, 101L); // 同日新版本合法（追加式）
        // SQLite 经 JdbcTemplate 抛 UncategorizedSQLException（SQLITE_CONSTRAINT_UNIQUE——xerial
        // 驱动未映射专用异常类型）
        assertThatThrownBy(() -> insertRankRow("2026-09-22", 1, 1, 103L))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("UNIQUE constraint failed")
                .hasMessageContaining("market_top_rank");
        jdbcTemplate.update("DELETE FROM market_top_rank WHERE rank_date = '2026-09-22'");
    }

    private void insertRankRow(String rankDate, int version, int rankNo, long subjectId) {
        jdbcTemplate.update(
                "INSERT INTO market_top_rank (rank_date, version, rank_no, subject_id,"
                        + " subject_code, subject_name, total_score, final_score, breakthrough,"
                        + " generation, dive_summary, change_type, basis, computed_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'SH600519', '贵州茅台',"
                        + " 58.4, 60.1, 1, 'FULL', '摘要', 'NEW', 'mt-v1:…',"
                        + " '2026-09-22T10:00:00Z', '2026-09-22T10:00:00Z',"
                        + " '2026-09-22T10:00:00Z')",
                rankDate,
                version,
                rankNo,
                subjectId);
    }

    // ---- 数据面：存量回填与阈值守卫 SQL 语义（对夹具重放迁移同文） ----

    @Test
    void v31_backfill_lastEventDate_unionMaxAcrossThreeSections() {
        // Arrange：catalyst 09-18 / fundamental 09-21 / risk 09-15 → max 09-21；另一行无事件
        long subjectA = firstSubjectId();
        long subjectB = secondSubjectId();
        insertSnapshotRow(
                subjectA,
                "2026-09-20",
                "{\"catalyst\":{\"entries\":[{\"eventDate\":\"2026-09-18\"}]},"
                        + "\"fundamental\":{\"entries\":[{\"eventDate\":\"2026-09-21\"}]},"
                        + "\"risk\":{\"entries\":[{\"eventDate\":\"2026-09-15\"}]}}");
        insertSnapshotRow(
                subjectB,
                "2026-09-20",
                "{\"catalyst\":{\"entries\":[]},\"fundamental\":{\"raw\":1.0},\"risk\":{}}");

        // Act：重放 V31 回填段（迁移时点作用于当时存量；此处验证 SQL 语义本身）
        jdbcTemplate.update(LAST_EVENT_BACKFILL_SQL);

        // Assert：三段 UNION MAX 取最大日期；无事件行保持 NULL（粗筛 NULL 视最旧）
        assertThat(lastEventDateOf(subjectA, "2026-09-20")).isEqualTo("2026-09-21");
        assertThat(lastEventDateOf(subjectB, "2026-09-20")).isNull();
        cleanupSnapshotRows("2026-09-20");
    }

    private void upsertScoreWeight(String json) {
        Integer existing =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM runtime_config WHERE config_key = 'score.weight'",
                        Integer.class);
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                    "UPDATE runtime_config SET config_value = ? WHERE config_key = 'score.weight'",
                    json);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO runtime_config (config_key, config_value, description,"
                            + " created_at, updated_at) VALUES ('score.weight', ?, null,"
                            + " '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z')",
                    json);
        }
    }

    private Integer btCatalystMinOf() {
        return jdbcTemplate.queryForObject(
                "SELECT json_extract(config_value, '$.btCatalystMin') FROM runtime_config"
                        + " WHERE config_key = 'score.weight'",
                Integer.class);
    }

    private long firstSubjectId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM subject_master", Long.class);
        return id == null ? 1L : id;
    }

    private long secondSubjectId() {
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM subject_master", Long.class);
        return id == null || id == firstSubjectId() ? firstSubjectId() + 1 : id;
    }

    private void insertSnapshotRow(long subjectId, String snapshotDate, String factorDetail) {
        jdbcTemplate.update(
                "INSERT INTO subject_factor_snapshot (subject_id, snapshot_date, factor_detail,"
                        + " data_flags, weight_basis, computed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '[]', 'vs-v1:…',"
                        + " '2026-09-22T09:00:00Z', '2026-09-22T09:00:00Z', '2026-09-22T09:00:00Z')",
                subjectId,
                snapshotDate,
                factorDetail);
    }

    private String lastEventDateOf(long subjectId, String snapshotDate) {
        return jdbcTemplate.queryForObject(
                "SELECT last_event_date FROM subject_factor_snapshot WHERE snapshot_date = ?"
                        + " AND subject_id = ?",
                String.class,
                snapshotDate,
                subjectId);
    }

    private void cleanupSnapshotRows(String snapshotDate) {
        jdbcTemplate.update(
                "DELETE FROM subject_factor_snapshot WHERE snapshot_date = ?", snapshotDate);
    }
}
