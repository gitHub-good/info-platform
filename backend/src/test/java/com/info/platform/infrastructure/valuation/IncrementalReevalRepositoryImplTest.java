package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import com.info.platform.domain.valuation.IncrementalReevalRepository.PendingLink;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import com.info.platform.domain.valuation.IncrementalReevalRepository.SubjectScore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IncrementalReevalRepositoryImpl 集成测试（M22 T190，V33 表）：消费判重（LEFT JOIN + 窗界 + 重要度阈值枚举序）/
 * insertScanned UNIQUE(event_id) 幂等 / 状态机转移留痕（RECOMPUTED→NO_LINK·DEFERRED·LINKED·FAILED）/
 * findPendingLink 挂起轮语义 / lastEventVersionAt 时效口径终点 / subjects 前后分读取。夹具用远未来事件 id 隔离，逐轮物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class IncrementalReevalRepositoryImplTest {

    private static final String NOW = "2026-09-28T02:00:00Z";

    private static final Instant CREATED_BEFORE = Instant.parse("2026-09-28T02:00:00Z");

    private static final Instant CREATED_SINCE = Instant.parse("2026-09-27T02:00:00Z");

    @Autowired private IncrementalReevalRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM incremental_reeval_log WHERE event_id >= 990000");
        jdbcTemplate.update("DELETE FROM event_item WHERE id >= 990000");
        jdbcTemplate.update("DELETE FROM market_top_batch WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM market_top_rank WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update(
                "DELETE FROM subject_factor_snapshot WHERE snapshot_date = '2099-12-31'");
    }

    private long insertEvent(String importance, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, subjects, event_date, created_at, updated_at)"
                        + " VALUES (?, 'EARNINGS_FORECAST', '摘要', '[]', 'BULLISH', ?,"
                        + " '[{\"code\":\"SH600519\",\"name\":\"贵州茅台\"}]', '2026-09-28', ?, ?)",
                nextNewsId(),
                importance,
                createdAt,
                createdAt);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(id) FROM event_item WHERE created_at = ?",
                        Long.class,
                        createdAt);
        return id == null ? -1 : id;
    }

    private long nextNewsId() {
        Long max =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(news_id), 990000) FROM event_item WHERE news_id >= 990000",
                        Long.class);
        return (max == null ? 990000 : max) + 1;
    }

    private static ReevalEvent reevalEventOf(long eventId, Importance importance) {
        return new ReevalEvent(
                eventId, NOW, "摘要", importance, List.of("SH600519"), List.of("食品饮料"));
    }

    @Test
    void findUnconsumedEvents_leftJoinDedupWindowAndImportanceThreshold() {
        long highId = insertEvent("HIGH", "2026-09-28T01:00:00Z");
        long mediumId = insertEvent("MEDIUM", "2026-09-28T01:00:00Z");
        long lowId = insertEvent("LOW", "2026-09-28T01:00:00Z");
        long staleId = insertEvent("HIGH", "2026-09-26T00:00:00Z"); // 24h 补跑窗外
        long bufferedId = insertEvent("HIGH", "2026-09-28T01:59:50Z"); // 20s 落库缓冲内
        repository.insertScanned(reevalEventOf(highId, Importance.HIGH), NOW); // 已消费

        // HIGH 阈值：MEDIUM/LOW 不触发（枚举序比较）；已消费/窗外排除
        List<ReevalEvent> highScan =
                repository.findUnconsumedEvents(
                        Importance.HIGH, CREATED_BEFORE, CREATED_SINCE, 200);
        assertThat(highScan).extracting(ReevalEvent::eventId).containsExactly(bufferedId);

        // MEDIUM 阈值：HIGH 已消费除外，MEDIUM/缓冲外窗内全收；LOW 不收
        List<ReevalEvent> mediumScan =
                repository.findUnconsumedEvents(
                        Importance.MEDIUM, CREATED_BEFORE, CREATED_SINCE, 200);
        assertThat(mediumScan)
                .extracting(ReevalEvent::eventId)
                .containsExactly(mediumId, bufferedId)
                .doesNotContain(lowId, highId, staleId);

        assertThat(highScan).allSatisfy(e -> assertThat(e.subjectCodes()).contains("SH600519"));
    }

    @Test
    void insertScanned_uniqueEventIdIdempotent() {
        long eventId = insertEvent("HIGH", "2026-09-28T01:00:00Z");

        repository.insertScanned(reevalEventOf(eventId, Importance.HIGH), NOW);
        int second = repository.insertScanned(reevalEventOf(eventId, Importance.HIGH), NOW);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM incremental_reeval_log WHERE event_id = ?",
                        Integer.class,
                        eventId);
        assertThat(second).isZero();
        assertThat(count).isEqualTo(1);
        assertThat(
                        repository.findUnconsumedEvents(
                                Importance.HIGH, CREATED_BEFORE, CREATED_SINCE, 200))
                .extracting(ReevalEvent::eventId)
                .doesNotContain(eventId);
    }

    @Test
    void stateMachineTransitions_persistedInOrder() {
        long eventId = insertEvent("HIGH", "2026-09-28T01:00:00Z");
        repository.insertScanned(reevalEventOf(eventId, Importance.HIGH), NOW);

        // SCANNED → RECOMPUTED（前后分 + 重算时刻 + 判定结果）
        repository.markRecomputed(
                List.of(eventId),
                "[{\"id\":1,\"code\":\"SH600519\",\"before\":55.0,\"after\":70.0}]",
                "2026-09-28T02:00:05Z",
                true,
                NOW);
        assertThat(statusOf(eventId)).isEqualTo("RECOMPUTED");
        assertThat(fieldOf(eventId, "judge_passed")).isEqualTo("1");
        assertThat(fieldOf(eventId, "subjects_json")).contains("before");

        // RECOMPUTED → DEFERRED（联动间隔防抖）
        repository.markStatus(List.of(eventId), "DEFERRED", NOW);
        assertThat(statusOf(eventId)).isEqualTo("DEFERRED");

        // DEFERRED → LINKED（终态：联动版本 + 时刻）
        repository.markLinked(List.of(eventId), 3, "2026-09-28T02:05:00Z", NOW);
        assertThat(statusOf(eventId)).isEqualTo("LINKED");
        assertThat(fieldOf(eventId, "top_version")).isEqualTo("3");

        // LINKED 不再属挂起轮
        assertThat(repository.findPendingLink())
                .extracting(PendingLink::eventId)
                .doesNotContain(eventId);
    }

    @Test
    void markFailed_recordsErrorMessage() {
        long eventId = insertEvent("HIGH", "2026-09-28T01:00:00Z");
        repository.insertScanned(reevalEventOf(eventId, Importance.HIGH), NOW);

        repository.markFailed(List.of(eventId), "投影查询失败", NOW);

        assertThat(statusOf(eventId)).isEqualTo("FAILED");
        assertThat(fieldOf(eventId, "error_message")).contains("投影查询失败");
        // FAILED 终态不属挂起轮（次日全量自愈，对账 SQL 可查）
        assertThat(repository.findPendingLink())
                .extracting(PendingLink::eventId)
                .doesNotContain(eventId);
    }

    @Test
    void findPendingLink_returnsOnlyJudgePassedPendingRounds() {
        long linkedId = insertEvent("HIGH", "2026-09-28T01:00:00Z");
        long deferredId = insertEvent("HIGH", "2026-09-28T01:01:00Z");
        long noLinkId = insertEvent("HIGH", "2026-09-28T01:02:00Z");
        long unjudgedId = insertEvent("HIGH", "2026-09-28T01:03:00Z");
        for (long id : List.of(linkedId, deferredId, noLinkId, unjudgedId)) {
            repository.insertScanned(reevalEventOf(id, Importance.HIGH), NOW);
        }
        repository.markRecomputed(List.of(linkedId), "[]", "2026-09-28T02:00:05Z", true, NOW);
        repository.markStatus(List.of(linkedId), "LINKED", NOW);
        repository.markRecomputed(List.of(deferredId), "[]", "2026-09-28T02:00:05Z", true, NOW);
        repository.markStatus(List.of(deferredId), "DEFERRED", NOW);
        repository.markRecomputed(List.of(noLinkId), "[]", "2026-09-28T02:00:05Z", false, NOW);
        repository.markStatus(List.of(noLinkId), "NO_LINK", NOW);
        repository.markRecomputed(
                List.of(unjudgedId), "[]", "2026-09-28T02:00:05Z", false, NOW); // 未过阈非挂起

        List<PendingLink> pending = repository.findPendingLink();

        assertThat(pending).extracting(PendingLink::eventId).containsExactly(deferredId);
    }

    @Test
    void findLastEventVersionAt_returnsLatestEventVersionCreatedAt() {
        jdbcTemplate.update(
                "INSERT INTO market_top_batch (rank_date, version, trigger_source, snapshot_date,"
                        + " funnel_stats, degraded, dropped_subjects, dive_cost_micros,"
                        + " dive_llm_calls, basis, created_at, updated_at)"
                        + " VALUES ('2099-12-31', 1, 'DAILY', '2099-12-31', '{}', 0, '[]', 0, 0,"
                        + " 'mt-v1:…', '2099-12-31T10:00:00Z', '2099-12-31T10:00:00Z')");
        jdbcTemplate.update(
                "INSERT INTO market_top_batch (rank_date, version, trigger_source, snapshot_date,"
                        + " funnel_stats, degraded, dropped_subjects, dive_cost_micros,"
                        + " dive_llm_calls, basis, created_at, updated_at)"
                        + " VALUES ('2099-12-31', 2, 'EVENT', '2099-12-31', '{}', 0, '[]', 0, 0,"
                        + " 'mt-v1:…', '2099-12-31T10:05:00Z', '2099-12-31T10:05:00Z')");
        jdbcTemplate.update(
                "INSERT INTO market_top_batch (rank_date, version, trigger_source, snapshot_date,"
                        + " funnel_stats, degraded, dropped_subjects, dive_cost_micros,"
                        + " dive_llm_calls, basis, created_at, updated_at)"
                        + " VALUES ('2099-12-31', 3, 'EVENT', '2099-12-31', '{}', 0, '[]', 0, 0,"
                        + " 'mt-v1:…', '2099-12-31T10:20:00Z', '2099-12-31T10:20:00Z')");

        assertThat(repository.findLastEventVersionAt("2099-12-31"))
                .contains("2099-12-31T10:20:00Z");
        assertThat(repository.findLastEventVersionAt("2099-12-30")).isEmpty();
    }

    @Test
    void findScoresBySubjectIds_readsTodayRowsWithCodes() {
        long subjectId = firstSubjectId();
        jdbcTemplate.update(
                "INSERT INTO subject_factor_snapshot (subject_id, snapshot_date, total_score,"
                        + " factor_detail, data_flags, weight_basis, computed_at, created_at,"
                        + " updated_at) VALUES (?, '2099-12-31', 55.5, '{}', '[]', 'vs-v1:…',"
                        + " '2099-12-31T10:00:00Z', '2099-12-31T10:00:00Z',"
                        + " '2099-12-31T10:00:00Z')",
                subjectId);

        List<SubjectScore> scores =
                repository.findScoresBySubjectIds("2099-12-31", List.of(subjectId, subjectId + 1));

        assertThat(scores).hasSize(1);
        assertThat(scores.get(0).subjectId()).isEqualTo(subjectId);
        assertThat(scores.get(0).totalScore()).isEqualTo(55.5);
        assertThat(scores.get(0).subjectCode()).isNotBlank();
    }

    private long firstSubjectId() {
        Long id = jdbcTemplate.queryForObject("SELECT MIN(id) FROM subject_master", Long.class);
        return id == null ? 1L : id;
    }

    private String statusOf(long eventId) {
        return fieldOf(eventId, "status");
    }

    private String fieldOf(long eventId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM incremental_reeval_log WHERE event_id = ?",
                String.class,
                eventId);
    }
}
