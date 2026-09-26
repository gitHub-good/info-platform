package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * OBS-04 留痕行归位集成测试（T130，M16 方案 §4.2）：news_analysis.l2_status='EXTRACTED' 但 event_item 无对应行的孤儿行 回置
 * FAILED（attempts 留痕不动）；健康 EXTRACTED 行（event_item 存在）不动；归位幂等（再跑匹配 0 行）。 V23 表由 Flyway 内存库建出；t130_
 * 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class L2TraceRepairTest {

    @Autowired private L2TraceRepair repair;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceId;

    @BeforeEach
    void setUp() {
        InfoSource source =
                InfoSource.create(
                        "t130_src",
                        "t130源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t130",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM event_item WHERE news_id IN (SELECT id FROM news_item WHERE source_id = ?)",
                sourceId);
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id = ?)",
                sourceId);
        jdbcTemplate.update("DELETE FROM news_item WHERE source_id = ?", sourceId);
        jdbcTemplate.update("DELETE FROM info_source WHERE id = ?", sourceId);
    }

    private long insertNewsAndAnalysis(String externalId, String l2Status, int l2Attempts) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '摘要', 'https://example.com/t130', ?, ?, ?, 1, ?, ?)",
                sourceId,
                externalId,
                "T130 标题 " + externalId,
                now,
                now,
                "fp-t130-" + externalId,
                now,
                now);
        long newsId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE external_id = ?", Long.class, externalId);
        jdbcTemplate.update(
                "INSERT INTO news_analysis (news_id, l0_result, l1_status, l2_status, l2_attempts,"
                        + " created_at, updated_at) VALUES (?, 'PASS', 'DONE', ?, ?, ?, ?)",
                newsId,
                l2Status,
                l2Attempts,
                now,
                now);
        return newsId;
    }

    @Test
    void repair_orphanExtractedRow_failsWithAttemptsUntouched() {
        // Arrange：孤儿 EXTRACTED 行（event_item 已删，BUG-01 处置残留形态）attempts=1 留痕
        long orphan = insertNewsAndAnalysis("t130_orphan", "EXTRACTED", 1);

        // Act
        int repaired = repair.repairOrphanExtractedRows();

        // Assert
        assertThat(repaired).isEqualTo(1);
        assertThat(readL2Status(orphan)).isEqualTo("FAILED");
        assertThat(readL2Attempts(orphan)).isEqualTo(1);
    }

    @Test
    void repair_healthyExtractedRowWithEvent_untouched() {
        // Arrange：健康 EXTRACTED 行（event_item 存在）
        long healthy = insertNewsAndAnalysis("t130_healthy", "EXTRACTED", 0);
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, event_date, created_at, updated_at)"
                        + " VALUES (?, 'POLICY_RELEASE', '摘要', '[]', 'NEUTRAL', 'MEDIUM', '2026-09-22', ?, ?)",
                healthy,
                Instant.now().toString(),
                Instant.now().toString());

        // Act
        int repaired = repair.repairOrphanExtractedRows();

        // Assert：健康行不动（本次轮无孤儿 → 0；status 保持 EXTRACTED）
        assertThat(repaired).isZero();
        assertThat(readL2Status(healthy)).isEqualTo("EXTRACTED");
        jdbcTemplate.update("DELETE FROM event_item WHERE news_id = ?", healthy);
    }

    @Test
    void repair_idempotent_secondRunMatchesNothing() {
        // Arrange
        long orphan = insertNewsAndAnalysis("t130_twice", "EXTRACTED", 0);
        repair.repairOrphanExtractedRows();

        // Act
        int second = repair.repairOrphanExtractedRows();

        // Assert：归位后行不再匹配（FAILED 非目标态），幂等
        assertThat(second).isZero();
        assertThat(readL2Status(orphan)).isEqualTo("FAILED");
    }

    private String readL2Status(long newsId) {
        return jdbcTemplate.queryForObject(
                "SELECT l2_status FROM news_analysis WHERE news_id = ?", String.class, newsId);
    }

    private int readL2Attempts(long newsId) {
        Integer attempts =
                jdbcTemplate.queryForObject(
                        "SELECT l2_attempts FROM news_analysis WHERE news_id = ?",
                        Integer.class,
                        newsId);
        return attempts == null ? 0 : attempts;
    }
}
