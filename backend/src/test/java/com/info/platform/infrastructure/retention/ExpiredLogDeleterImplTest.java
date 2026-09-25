package com.info.platform.infrastructure.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.retention.ExpiredLogDeleter;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ExpiredLogDeleterImpl 集成测试（T70，方案 §6 删除端口组 / ADR-0036 §3）：SQLite 共享内存库 + Flyway 建表后验证删除端口语义——
 * 空删返回 0 / 窗口内行全保留 / created_at 恰等于 cutoff 的边界行保留（严格早于）/ cutoff 带亚秒精度先整秒截断 /
 * limit 拆批（分多批、计数合计准确、循环终止）/ 业务表零触碰（白名单只动四留痕表）。
 *
 * <p>数据经唯一标记（job_name='RetentionTestJob' 等）插入与清理，不与其他测试类共享行；时间戳全部用
 * 2020~2026 显式常量，cutoff 断言不受真实时钟影响。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExpiredLogDeleterImplTest {

    /** 测试行标记（@AfterEach 按标记清理，不污染共享内存库）。 */
    private static final String MARK = "RetentionTest";

    /** 边界基准时刻（整秒 ISO-8601，与四表 created_at 存储格式一致）。 */
    private static final Instant CUTOFF = Instant.parse("2026-08-23T00:00:00Z");

    @Autowired private ExpiredLogDeleter deleter;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM job_execution_log WHERE job_name = ?", MARK);
        jdbcTemplate.update("DELETE FROM data_source_event WHERE source_code = ?", MARK);
        jdbcTemplate.update("DELETE FROM llm_call_log WHERE scene_key = ?", MARK);
        jdbcTemplate.update("DELETE FROM reading_event WHERE content_ref = ?", MARK);
    }

    // —— 插桩（各表仅必填列，时间显式给定） ——

    private void insertJobLog(String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO job_execution_log (job_name, start_time, status, created_at,"
                        + " updated_at) VALUES (?, ?, 'SUCCESS', ?, ?)",
                MARK,
                createdAt,
                createdAt,
                createdAt);
    }

    private void insertDataSourceEvent(String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO data_source_event (source_code, event_type, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?)",
                MARK,
                createdAt,
                createdAt);
    }

    private void insertLlmCallLog(String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO llm_call_log (scene_key, status, created_at, updated_at)"
                        + " VALUES (?, 'SUCCESS', ?, ?)",
                MARK,
                createdAt,
                createdAt);
    }

    private void insertReadingEvent(String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO reading_event (user_id, content_type, content_ref, created_at,"
                        + " updated_at) VALUES (1, 'POLICY', ?, ?, ?)",
                MARK,
                createdAt,
                createdAt);
    }

    private long markerCount(String table) {
        String column =
                switch (table) {
                    case "job_execution_log" -> "job_name";
                    case "data_source_event" -> "source_code";
                    case "llm_call_log" -> "scene_key";
                    default -> "content_ref";
                };
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, MARK);
    }

    @Test
    void deleteExpiredBefore_emptySlice_returnsZero() {
        // Arrange：早于任何测试行（全部 2026 年）的 cutoff；四表无匹配行
        // Act
        long deleted = deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG,
                Instant.parse("2020-01-01T00:00:00Z"), 500);

        // Assert：空删返回 0（空轮照常返回，不抛出）
        assertThat(deleted).isZero();
    }

    @Test
    void deleteExpiredBefore_strictlyBeforeCutoff_boundaryRowKept() {
        // Arrange：边界行（恰等于 cutoff）、过期行（早 1 秒）、窗口内行（晚 1 秒）
        insertJobLog("2026-08-23T00:00:00Z");
        insertJobLog("2026-08-22T23:59:59Z");
        insertJobLog("2026-08-23T00:00:01Z");

        // Act
        long deleted = deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG, CUTOFF, 500);

        // Assert：仅严格早于 cutoff 的行删除；边界行与窗口内行保留（PRD 场景 1/2 红线）
        assertThat(deleted).isEqualTo(1);
        List<String> remaining =
                jdbcTemplate.queryForList(
                        "SELECT created_at FROM job_execution_log WHERE job_name = ? ORDER BY"
                                + " created_at",
                        String.class,
                        MARK);
        assertThat(remaining).containsExactly("2026-08-23T00:00:00Z", "2026-08-23T00:00:01Z");
    }

    @Test
    void deleteExpiredBefore_subSecondCutoff_truncatedToWholeSecondBeforeCompare() {
        // Arrange：cutoff 带 .750 亚秒精度——须先整秒截断为 00:00:00Z 再比较（两侧同为整秒定长文本，字典序=时间序）；
        // 不截断则边界串 "2026-08-23T00:00:00.750Z" > "2026-08-23T00:00:00Z"，边界行会被误删
        insertJobLog("2026-08-23T00:00:00Z");
        insertJobLog("2026-08-22T23:59:59Z");

        // Act
        long deleted = deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG,
                Instant.parse("2026-08-23T00:00:00.750Z"), 500);

        // Assert：截断后判定——边界行保留、仅 1 秒前行删除
        assertThat(deleted).isEqualTo(1);
        assertThat(markerCount("job_execution_log")).isEqualTo(1);
    }

    @Test
    void deleteExpiredBefore_limitSplitsBatches_countsAccurateAndLoopTerminates() {
        // Arrange：7 行过期（cutoff 前各自整秒不同）+ 1 行窗口内（data_source_event），limit=3 → 3+3+1 三批
        for (int i = 0; i < 7; i++) {
            insertDataSourceEvent(String.format("2026-08-22T23:59:%02dZ", i));
        }
        insertDataSourceEvent("2026-09-01T00:00:00Z");

        // Act：循环调用至返回值 < limit（分批调用方语义，服务层循环删同端口）
        long total = 0;
        long deleted;
        int calls = 0;
        do {
            deleted = deleter.deleteExpiredBefore(RetentionLogTable.DATA_SOURCE_EVENT, CUTOFF, 3);
            total += deleted;
            calls++;
        } while (deleted == 3);

        // Assert：合计 = 过期行数、批次数正确、终批返回余量、窗口内行保留
        assertThat(total).isEqualTo(7);
        assertThat(calls).isEqualTo(3);
        assertThat(markerCount("data_source_event")).isEqualTo(1);
    }

    @Test
    void deleteExpiredBefore_eachOfFourTables_deletesItsOwnExpiredRows() {
        // Arrange：四表各 1 行过期 + 1 行窗口内
        insertJobLog("2026-08-01T00:00:00Z");
        insertJobLog("2026-09-10T00:00:00Z");
        insertDataSourceEvent("2026-08-01T00:00:00Z");
        insertDataSourceEvent("2026-09-10T00:00:00Z");
        insertLlmCallLog("2026-08-01T00:00:00Z");
        insertLlmCallLog("2026-09-10T00:00:00Z");
        insertReadingEvent("2026-08-01T00:00:00Z");
        insertReadingEvent("2026-09-10T00:00:00Z");

        // Act：逐表删（枚举序）
        long job = deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG, CUTOFF, 500);
        long dse = deleter.deleteExpiredBefore(RetentionLogTable.DATA_SOURCE_EVENT, CUTOFF, 500);
        long llm = deleter.deleteExpiredBefore(RetentionLogTable.LLM_CALL_LOG, CUTOFF, 500);
        long reading = deleter.deleteExpiredBefore(RetentionLogTable.READING_EVENT, CUTOFF, 500);

        // Assert：各删 1 行、窗口内行保留
        assertThat(job).isEqualTo(1);
        assertThat(dse).isEqualTo(1);
        assertThat(llm).isEqualTo(1);
        assertThat(reading).isEqualTo(1);
        assertThat(markerCount("job_execution_log")).isEqualTo(1);
        assertThat(markerCount("data_source_event")).isEqualTo(1);
        assertThat(markerCount("llm_call_log")).isEqualTo(1);
        assertThat(markerCount("reading_event")).isEqualTo(1);
    }

    @Test
    void deleteExpiredBefore_whitelistOnly_businessTablesUntouched() {
        // Arrange：四表各插入过期行 + 记录业务表基线行数（种子与迁移数据）
        insertJobLog("2026-08-01T00:00:00Z");
        insertDataSourceEvent("2026-08-01T00:00:00Z");
        insertLlmCallLog("2026-08-01T00:00:00Z");
        insertReadingEvent("2026-08-01T00:00:00Z");
        List<String> businessTables =
                List.of(
                        "subject_master",
                        "policy_item",
                        "anomaly_event",
                        "push_record",
                        "ai_brief",
                        "prompt_template",
                        "runtime_config");
        List<Long> before =
                businessTables.stream()
                        .map(t -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + t, Long.class))
                        .toList();

        // Act：四表全删（PRD 场景 6 白名单红线）
        for (RetentionLogTable table : RetentionLogTable.values()) {
            deleter.deleteExpiredBefore(table, CUTOFF, 500);
        }

        // Assert：业务表行数逐一不变（白名单只动四留痕表）
        for (int i = 0; i < businessTables.size(); i++) {
            long after =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + businessTables.get(i), Long.class);
            assertThat(after)
                    .as("业务表 %s 行数不应变化", businessTables.get(i))
                    .isEqualTo(before.get(i));
        }
    }

    @Test
    void deleteExpiredBefore_nullArgsOrNonPositiveLimit_rejected() {
        // Arrange / Act / Assert：入参防御（null 表/时刻、非正 limit 拒绝——白名单端口的显式契约）
        assertThatThrownBy(() -> deleter.deleteExpiredBefore(null, CUTOFF, 500))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG, null, 500))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> deleter.deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG, CUTOFF, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
