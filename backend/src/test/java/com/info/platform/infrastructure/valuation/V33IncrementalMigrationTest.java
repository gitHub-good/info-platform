package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V33 迁移语义集成测试（M22 T190/T191，方案 §4.1 DDL）：context 启动时 Flyway 已应用 V33——结构面断言（留痕表 / 两 ALTER 列在位 /
 * UNIQUE(event_id)）与增量列复位语义（increment_at 由增量 UPSERT 写入、全量 UPSERT 显式置 NULL 复位——value-score 双层时间戳依据）。
 */
@SpringBootTest
@ActiveProfiles("test")
class V33IncrementalMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void v33_structure_logTableAndColumnsPresent() {
        Integer logTable =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name ="
                                + " 'incremental_reeval_log'",
                        Integer.class);
        Integer logIndex =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name ="
                                + " 'idx_ireval_created'",
                        Integer.class);
        Integer incrementAt =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pragma_table_info('subject_factor_snapshot')"
                                + " WHERE name = 'increment_at'",
                        Integer.class);
        Integer triggerEvents =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pragma_table_info('market_top_batch')"
                                + " WHERE name = 'trigger_events'",
                        Integer.class);
        assertThat(logTable).isEqualTo(1);
        assertThat(logIndex).isEqualTo(1);
        assertThat(incrementAt).isEqualTo(1);
        assertThat(triggerEvents).isEqualTo(1);
    }

    @Test
    void v33_structure_uniqueEventIdEnforced() {
        jdbcTemplate.update(
                "INSERT INTO incremental_reeval_log (event_id, event_created_at, subjects_json,"
                        + " judge_passed, status, created_at, updated_at)"
                        + " VALUES (990001, '2026-09-28T01:00:00Z', '[]', 0, 'SCANNED',"
                        + " '2026-09-28T02:00:00Z', '2026-09-28T02:00:00Z')");
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO incremental_reeval_log (event_id,"
                                                + " event_created_at, subjects_json, judge_passed,"
                                                + " status, created_at, updated_at)"
                                                + " VALUES (990001, '2026-09-28T01:00:00Z', '[]',"
                                                + " 0, 'SCANNED', '2026-09-28T02:00:00Z',"
                                                + " '2026-09-28T02:00:00Z')"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("UNIQUE constraint failed")
                .hasMessageContaining("incremental_reeval_log");
        jdbcTemplate.update("DELETE FROM incremental_reeval_log WHERE event_id = 990001");
    }

    @Test
    void v33_incrementAtColumn_nullableAndResettableByFullUpsert() {
        long subjectId = firstSubjectId();
        insertSnapshotRow(subjectId, "2099-12-31", "'2099-12-31T10:00:00Z'");
        // 全量 UPSERT 显式置 NULL → 复位（盘后重跑清除增量标注，双层时间戳回落盘后基准）
        jdbcTemplate.update(
                "UPDATE subject_factor_snapshot SET increment_at = NULL"
                        + " WHERE snapshot_date = '2099-12-31' AND subject_id = ?",
                subjectId);
        String incrementAt =
                jdbcTemplate.queryForObject(
                        "SELECT increment_at FROM subject_factor_snapshot"
                                + " WHERE snapshot_date = '2099-12-31' AND subject_id = ?",
                        String.class,
                        subjectId);
        assertThat(incrementAt).isNull();
        jdbcTemplate.update(
                "DELETE FROM subject_factor_snapshot WHERE snapshot_date = '2099-12-31'");
    }

    private long firstSubjectId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM subject_master", Long.class);
        return id == null ? 1L : id;
    }

    private void insertSnapshotRow(long subjectId, String snapshotDate, String incrementAtLiteral) {
        jdbcTemplate.update(
                "INSERT INTO subject_factor_snapshot (subject_id, snapshot_date, total_score,"
                        + " factor_detail, data_flags, weight_basis, computed_at, increment_at,"
                        + " created_at, updated_at) VALUES (?, ?, 50.0, '{}', '[]', 'vs-v1:…',"
                        + " '2099-12-31T10:00:00Z', "
                        + incrementAtLiteral
                        + ", '2099-12-31T10:00:00Z', '2099-12-31T10:00:00Z')",
                subjectId,
                snapshotDate);
    }
}
