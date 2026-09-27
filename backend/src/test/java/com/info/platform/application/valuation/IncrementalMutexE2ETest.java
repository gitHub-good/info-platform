package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.RunningJobIndicator;
import com.info.platform.application.markettop.IncrementalTopService;
import com.info.platform.application.valuation.IncrementalReevalService.ReevalReport;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 增量 × 盘后全量互斥 E2E 集成回归（M22 T192，故事 3 场景 3 + 方案 §3.3 互斥层②回归）：真实仓储/服务协作（共享内存 SQLite + 真实
 * IncrementalTopService——LLM Mock），仅让路面板（RunningJobIndicator 端口）注入可控替身。
 *
 * <p>断言面：①重 Job 运行中 tick 整体让路且零写入（无留痕行 / 快照 increment_at 不动 / 无 EVENT 版本）；②让路解除后同一事件
 * 下轮重扫完成增量覆盖（increment_at 落列 + 留痕非 FAILED + 消费判重成立）；③守卫零改动面（重 Job 键仍在注册表、上下文让路端口接线为 JobExecutor
 * 共享执行器——M20/M21 Job 类零改动）+ 自愈复位（全量 UPSERT 显式置 NULL 清除增量标注）。
 */
@SpringBootTest
@ActiveProfiles("test")
class IncrementalMutexE2ETest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    /** 定长时钟（UTC 02:00 = 上海 10:00 → 增量口径当日 = 2026-09-28；扫描窗 [now−24h, now−20s]）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneOffset.UTC);

    private static final String NOW = "2026-09-28T02:00:00Z";

    @Autowired private IncrementalReevalRepository logRepository;

    @Autowired private FactorSnapshotRepository snapshotRepository;

    @Autowired private FactorSnapshotService factorSnapshotService;

    @Autowired private MarketTopRepository marketTopRepository;

    @Autowired private IncrementalTopService incrementalTopService;

    @Autowired private IncrementalReevalSettings settings;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JobRegistry jobRegistry;

    @Autowired private RunningJobIndicator contextIndicator;

    @Autowired private JdbcTemplate jdbcTemplate;

    /** 可控让路面板（互斥层②端口替身——真实接线断言见 guardWiring 测试）。 */
    private static final class MutableIndicator implements RunningJobIndicator {
        boolean heavyRunning;

        @Override
        public boolean isRunning(String jobKey) {
            return heavyRunning;
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM incremental_reeval_log WHERE event_id IN"
                        + " (SELECT id FROM event_item WHERE summary = 'E2E 事件驱动重评')");
        jdbcTemplate.update("DELETE FROM event_item WHERE summary = 'E2E 事件驱动重评'");
        jdbcTemplate.update("DELETE FROM market_top_batch WHERE rank_date = '2026-09-28'");
        jdbcTemplate.update("DELETE FROM market_top_rank WHERE rank_date = '2026-09-28'");
        jdbcTemplate.update(
                "DELETE FROM subject_factor_snapshot WHERE snapshot_date = '2026-09-28'");
    }

    private IncrementalReevalService service(RunningJobIndicator indicator) {
        return new IncrementalReevalService(
                logRepository,
                snapshotRepository,
                factorSnapshotService,
                marketTopRepository,
                incrementalTopService,
                settings,
                indicator,
                objectMapper,
                CLOCK);
    }

    // ---- 夹具：首个活跃标的 + 其 HIGH 事件 + 当日快照基线行 ----

    private FactorSnapshotRepository.SubjectRef firstSubject() {
        return snapshotRepository.findActiveSubjects().get(0);
    }

    private long seedHighEvent() {
        FactorSnapshotRepository.SubjectRef subject = firstSubject();
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, subjects, event_date, created_at, updated_at)"
                        + " VALUES (?, 'EARNINGS_FORECAST', 'E2E 事件驱动重评', '[]', 'BULLISH',"
                        + " 'HIGH', ?, '2026-09-28', '2026-09-28T01:00:00Z',"
                        + " '2026-09-28T01:00:00Z')",
                nextNewsId(),
                "[{\"code\":\"" + subject.code() + "\",\"name\":\"" + subject.name() + "\"}]");
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(id) FROM event_item WHERE summary = 'E2E 事件驱动重评'", Long.class);
        return id == null ? -1 : id;
    }

    private long nextNewsId() {
        Long max =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(news_id), 990000) FROM event_item WHERE news_id >= 990000",
                        Long.class);
        return (max == null ? 990000 : max) + 1;
    }

    private FactorSnapshotRow seedSnapshotRow() {
        FactorSnapshotRepository.SubjectRef subject = firstSubject();
        FactorSnapshotRow row =
                new FactorSnapshotRow(
                        subject.id(),
                        TODAY.toString(),
                        55.0,
                        40.0,
                        30.0,
                        50.0,
                        90.0,
                        55.0,
                        false,
                        "{}",
                        "[]",
                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80",
                        "2026-09-28T01:30:00Z",
                        "2026-09-28");
        snapshotRepository.upsertAll(List.of(row));
        return row;
    }

    // ---- ① 重 Job 运行中：让路且零写入（互斥层② E2E） ----

    @Test
    void tick_heavySnapshotRunning_defersRoundWithZeroWrites() {
        long eventId = seedHighEvent();
        seedSnapshotRow();
        MutableIndicator indicator = new MutableIndicator();
        indicator.heavyRunning = true; // 模拟 FACTOR_SNAPSHOT 运行中
        long eventVersionsBefore = eventVersionCount();

        ReevalReport report = service(indicator).tick();

        assertThat(report.detail()).contains("defer=heavy_running");
        // 零写入三断言：无留痕行 / 快照行 increment_at 不动（NULL）/ 无新增 EVENT 版本
        assertThat(logRowCount(eventId)).isZero();
        assertThat(incrementAtOf()).isNull();
        assertThat(eventVersionCount()).isEqualTo(eventVersionsBefore);
        // 事件未消费——让路不吞事件，下轮重扫（方案 §3.3 互斥层②语义）
        assertThat(
                        logRepository.findUnconsumedEvents(
                                Importance.HIGH,
                                Instant.parse(NOW),
                                Instant.parse("2026-09-27T02:00:00Z"),
                                200))
                .extracting(ReevalEvent::eventId)
                .contains(eventId);
    }

    // ---- ② 让路解除：同事件下轮重扫完成增量覆盖（让路的重试闭环） ----

    @Test
    void tick_afterHeavyFinishes_processesDeferredEventAndStampsIncrementAt() {
        long eventId = seedHighEvent();
        seedSnapshotRow();
        MutableIndicator indicator = new MutableIndicator();
        IncrementalReevalService reeval = service(indicator);

        // 首轮让路（重 Job 在跑）→ 次轮解除后完成增量重评
        indicator.heavyRunning = true;
        reeval.tick();
        indicator.heavyRunning = false;
        ReevalReport report = reeval.tick();

        assertThat(report.detail()).contains("scan:"); // 共享内存库允许他类遗留事件同轮并入——断言锚本事件不锚全局计数
        // 增量覆盖留痕：当日行 increment_at 落列（value-score 双层时间戳依据）
        assertThat(incrementAtOf()).isNotBlank();
        // 留痕状态机收口（判定/联动结果依数据而定——非 FAILED 即正常流转；FAILED 属对账修复面另有单测）
        assertThat(statusOf(eventId)).isNotEqualTo("FAILED").isNotNull();
        // 消费判重：事件已入留痕表不再重扫
        assertThat(
                        logRepository.findUnconsumedEvents(
                                Importance.HIGH,
                                Instant.parse(NOW),
                                Instant.parse("2026-09-27T02:00:00Z"),
                                200))
                .extracting(ReevalEvent::eventId)
                .doesNotContain(eventId);
    }

    // ---- ③ 守卫零改动面 + 自愈复位（故事 3 场景 1/2 回归） ----

    @Test
    void guardWiring_unchangedAndFullSnapshotResetsIncrementAt() {
        // 守卫零改动面：重 Job 键仍在注册表（18 Job 面零改动——M20/M21 Job 类未因增量接入重构）
        assertThat(jobRegistry.jobs())
                .extracting(ManagedJob::jobKey)
                .contains("FACTOR_SNAPSHOT", "MARKET_TOP_JOB", "INCREMENTAL_REEVAL");
        // 让路端口接线：上下文唯一 RunningJobIndicator 绑定共享执行器 JobExecutor（依赖倒置不变——
        // 类名字符串断言避免应用切片直依赖基础设施类，LayeredArchitectureTest 同口径）
        assertThat(contextIndicator.getClass().getSimpleName()).isEqualTo("JobExecutor");
        assertThat(contextIndicator.isRunning("FACTOR_SNAPSHOT")).isFalse();

        // 自愈复位：增量覆盖行经全量路径（17:30 快照 UPSERT 显式置 NULL）恢复无标注——双层时间戳回落盘后口径
        FactorSnapshotRow row = seedSnapshotRow();
        snapshotRepository.upsertAllIncremental(List.of(row), "2026-09-28T03:00:00Z");
        assertThat(incrementAtOf()).isEqualTo("2026-09-28T03:00:00Z");
        snapshotRepository.upsertAll(List.of(row));
        assertThat(incrementAtOf()).isNull();
    }

    // ---- 断言辅助 ----

    private String incrementAtOf() {
        FactorSnapshotRepository.SubjectRef subject = firstSubject();
        return jdbcTemplate.queryForObject(
                "SELECT increment_at FROM subject_factor_snapshot WHERE subject_id = ?"
                        + " AND snapshot_date = '2026-09-28'",
                String.class,
                subject.id());
    }

    private int logRowCount(long eventId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM incremental_reeval_log WHERE event_id = ?",
                        Integer.class,
                        eventId);
        return count == null ? 0 : count;
    }

    private String statusOf(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM incremental_reeval_log WHERE event_id = ?",
                String.class,
                eventId);
    }

    private long eventVersionCount() {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_top_batch WHERE rank_date = '2026-09-28'"
                                + " AND trigger_source = 'EVENT'",
                        Long.class);
        return count == null ? 0 : count;
    }
}
