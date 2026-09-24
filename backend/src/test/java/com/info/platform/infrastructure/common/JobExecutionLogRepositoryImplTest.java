package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.common.JobLogFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * JobExecutionLogRepositoryImpl 集成测试（T33）：SQLite 共享内存库 + Flyway V12 建表后，测 save 往返（STARTED 插入 →
 * SUCCESS/FAILED 更新）、byJobNameCursor 游标分页（newest-first、过滤、游标翻页）、findRecent。 {@code @SpringBootTest}
 * 启动完整上下文（含 Flyway 迁移）；{@code @Transactional} 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobExecutionLogRepositoryImplTest {

    @Autowired private JobExecutionLogRepository repository;

    private static final Instant T1 = Instant.parse("2026-09-22T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T01:00:05Z");

    @Test
    void save_newRecord_backfillsIdAndTimestamps_statusStarted() {
        // Act
        JobExecutionLog saved = repository.save(JobExecutionLog.create("PolicyFetchJob", T1));

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(JobExecutionStatus.STARTED);
        assertThat(saved.getJobName()).isEqualTo("PolicyFetchJob");
        assertThat(saved.getStartTime()).isEqualTo(T1);
        assertThat(saved.getEndTime()).isEmpty();
        assertThat(saved.getDurationMillis()).isEmpty();
        assertThat(saved.getProcessedCount()).isZero();
        assertThat(saved.getErrorCount()).isZero();
    }

    @Test
    void save_updatePath_markSuccess_flipsStatusAndFillsEndTimeDuration() {
        // Arrange：插入 STARTED
        JobExecutionLog saved = repository.save(JobExecutionLog.create("PolicyFetchJob", T1));
        assertThat(saved.getStatus()).isEqualTo(JobExecutionStatus.STARTED);

        // Act：翻转 SUCCESS
        saved.markSuccess(T2, 7, 1);
        JobExecutionLog updated = repository.save(saved);

        // Assert
        assertThat(updated.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
        assertThat(updated.getEndTime()).contains(T2);
        assertThat(updated.getDurationMillis()).contains(5000L);
        assertThat(updated.getProcessedCount()).isEqualTo(7);
        assertThat(updated.getErrorCount()).isEqualTo(1);
        assertThat(updated.getErrorMessage()).isEmpty();
    }

    @Test
    void save_updatePath_markFailed_setsErrorMessage() {
        JobExecutionLog saved = repository.save(JobExecutionLog.create("AnomalyDetectionJob", T1));
        saved.markFailed(T2, "java.lang.RuntimeException: boom", 0, 1);
        JobExecutionLog updated = repository.save(saved);

        assertThat(updated.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(updated.getErrorMessage().orElse("")).contains("boom");
        assertThat(updated.getErrorCount()).isEqualTo(1);
        assertThat(updated.getDurationMillis()).contains(5000L);
    }

    @Test
    void byJobNameCursor_noFilter_returnsNewestFirst() {
        // Arrange：3 条不同 id（自增），最早 T1、最晚 T2
        repository.save(JobExecutionLog.create("DailyRecommendationJob", T1));
        repository.save(JobExecutionLog.create("DailyRecommendationJob", T1));
        repository.save(JobExecutionLog.create("DailyRecommendationJob", T2));

        // Act
        List<JobExecutionLog> page = repository.byJobNameCursor(null, null, 10);

        // Assert：id DESC（newest-first）
        assertThat(page).hasSize(3);
        assertThat(page.get(0).getId()).isGreaterThan(page.get(1).getId());
        assertThat(page.get(1).getId()).isGreaterThan(page.get(2).getId());
    }

    @Test
    void byJobNameCursor_withJobNameFilter_onlyMatchesThatJob() {
        repository.save(JobExecutionLog.create("PolicyFetchJob", T1));
        repository.save(JobExecutionLog.create("AnomalyDetectionJob", T1));

        List<JobExecutionLog> page = repository.byJobNameCursor("PolicyFetchJob", null, 10);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getJobName()).isEqualTo("PolicyFetchJob");
    }

    @Test
    void byJobNameCursor_pagination_returnsBeforeCursor() {
        // Arrange：插入 3 条
        JobExecutionLog a = repository.save(JobExecutionLog.create("PushRetryJob", T1));
        JobExecutionLog b = repository.save(JobExecutionLog.create("PushRetryJob", T1));
        JobExecutionLog c = repository.save(JobExecutionLog.create("PushRetryJob", T2));

        // Act：首页取 2 条（newest-first：c, b），游标 = b.id
        List<JobExecutionLog> first = repository.byJobNameCursor("PushRetryJob", null, 2);
        assertThat(first).hasSize(2);
        assertThat(first.get(0).getId()).isEqualTo(c.getId());
        assertThat(first.get(1).getId()).isEqualTo(b.getId());

        // 第二页：id < b.id → 只剩 a
        List<JobExecutionLog> second = repository.byJobNameCursor("PushRetryJob", b.getId(), 2);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getId()).isEqualTo(a.getId());
    }

    @Test
    void findRecent_returnsNewestFirstLimited() {
        repository.save(JobExecutionLog.create("PushRetryJob", T1));
        repository.save(JobExecutionLog.create("PushRetryJob", T1));
        repository.save(JobExecutionLog.create("PushRetryJob", T2));

        List<JobExecutionLog> recent = repository.findRecent(2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getId()).isGreaterThan(recent.get(1).getId());
    }

    @Test
    void byJobNameCursor_emptyResult() {
        assertThat(repository.byJobNameCursor("NonexistentJob", null, 10)).isEmpty();
        assertThat(repository.byJobNameCursor(null, null, 10)).isEmpty();
    }

    @Test
    void countSince_andCountFailedSince_windowByCreatedAt_t42() {
        // Arrange：插入时 created_at=now；1 条 FAILED + 2 条 SUCCESS
        JobExecutionLog failed = repository.save(JobExecutionLog.create("PushRetryJob", T1));
        failed.markFailed(T2, "boom", 0, 1);
        repository.save(failed);
        repository.save(JobExecutionLog.create("PolicyFetchJob", T1));
        repository.save(JobExecutionLog.create("PolicyFetchJob", T2));

        // Act / Assert：滚动窗口内总数 3、失败 1；未来边界为 0
        assertThat(repository.countSince(Instant.now().minusSeconds(3600))).isEqualTo(3);
        assertThat(repository.countFailedSince(Instant.now().minusSeconds(3600))).isEqualTo(1);
        assertThat(repository.countSince(Instant.now().plusSeconds(60))).isZero();
        assertThat(repository.countFailedSince(Instant.now().plusSeconds(60))).isZero();
    }

    @Test
    void findFailedSince_returnsOnlyFailedRowsNewestFirst_t42() {
        // Arrange
        JobExecutionLog failedA = repository.save(JobExecutionLog.create("PushRetryJob", T1));
        failedA.markFailed(T2, "err-a", 0, 1);
        repository.save(failedA);
        repository.save(JobExecutionLog.create("PolicyFetchJob", T1)); // 非 FAILED 不返回
        JobExecutionLog failedB = repository.save(JobExecutionLog.create("LegacyGoneJob", T2));
        failedB.markFailed(T2.plusSeconds(1), "err-b", 0, 1);
        repository.save(failedB);

        // Act
        List<JobExecutionLog> failedRows =
                repository.findFailedSince(Instant.now().minusSeconds(3600), 10);

        // Assert：仅 FAILED 两行，newest-first（failedB 在前）
        assertThat(failedRows).hasSize(2);
        assertThat(failedRows.get(0).getJobName()).isEqualTo("LegacyGoneJob");
        assertThat(failedRows.get(1).getJobName()).isEqualTo("PushRetryJob");
    }

    // ==================== M9 T61：页码模式 + 状态筛选（byFilterPage/countByFilter，真实 SQLite）
    // ====================

    /** 落一条终态日志（save STARTED 后翻转），返回回填 id 的记录。 */
    private long saveFinished(String jobName, JobExecutionStatus status) {
        JobExecutionLog log = repository.save(JobExecutionLog.create(jobName, T1));
        if (status == JobExecutionStatus.SUCCESS) {
            log.markSuccess(T2, 5, 0);
        } else if (status == JobExecutionStatus.FAILED) {
            log.markFailed(T2, "boom", 0, 1);
        } else {
            return log.getId(); // STARTED 不翻转
        }
        return repository.save(log).getId();
    }

    @Test
    void byFilterPage_statusEquals_onlyMatchesThatStatus() {
        // Arrange：SUCCESS 2 条 + FAILED 1 条 + STARTED 1 条
        saveFinished("PolicyFetchJob", JobExecutionStatus.SUCCESS);
        saveFinished("PolicyFetchJob", JobExecutionStatus.SUCCESS);
        saveFinished("PolicyFetchJob", JobExecutionStatus.FAILED);
        saveFinished("AnomalyDetectionJob", JobExecutionStatus.STARTED);

        // Act + Assert：status=FAILED 等值过滤 → 仅 1 条；计数与页数据同口径
        JobLogFilter failed = new JobLogFilter(null, JobExecutionStatus.FAILED);
        assertThat(repository.countByFilter(failed)).isEqualTo(1);
        assertThat(repository.byFilterPage(failed, 1, 20))
                .allSatisfy(
                        log -> assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.FAILED));

        // status=SUCCESS → 2 条；STARTED（未翻转行）也能筛中
        assertThat(repository.countByFilter(new JobLogFilter(null, JobExecutionStatus.SUCCESS)))
                .isEqualTo(2);
        assertThat(
                        repository.byFilterPage(
                                new JobLogFilter(null, JobExecutionStatus.STARTED), 1, 20))
                .hasSize(1);
    }

    @Test
    void byFilterPage_jobNameAndStatusCombo_andSemantics() {
        // Arrange：PolicyFetchJob(SUCCESS/FAILED) + AnomalyDetectionJob(FAILED)
        saveFinished("PolicyFetchJob", JobExecutionStatus.SUCCESS);
        saveFinished("PolicyFetchJob", JobExecutionStatus.FAILED);
        saveFinished("AnomalyDetectionJob", JobExecutionStatus.FAILED);

        // Act + Assert：jobName + status 交集 → 仅 PolicyFetchJob 的 FAILED
        JobLogFilter combo = new JobLogFilter("PolicyFetchJob", JobExecutionStatus.FAILED);
        assertThat(repository.countByFilter(combo)).isEqualTo(1);
        List<JobExecutionLog> rows = repository.byFilterPage(combo, 1, 20);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getJobName()).isEqualTo("PolicyFetchJob");
        assertThat(rows.get(0).getStatus()).isEqualTo(JobExecutionStatus.FAILED);
    }

    @Test
    void byFilterPage_paginatesNewestFirst_andOutOfRangeEmpty() {
        // Arrange：5 条（插入序 id 升序）
        for (int i = 1; i <= 5; i++) {
            saveFinished("PushRetryJob", JobExecutionStatus.SUCCESS);
        }
        JobLogFilter filter = new JobLogFilter("PushRetryJob", null);

        // Act + Assert：第 1 页（size=2）newest-first（id 最大在前）；第 3 页剩 1 条；page=9 越界空列表
        List<JobExecutionLog> page1 = repository.byFilterPage(filter, 1, 2);
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).getId()).isGreaterThan(page1.get(1).getId());
        assertThat(repository.byFilterPage(filter, 3, 2)).hasSize(1);
        assertThat(repository.byFilterPage(filter, 9, 20)).isEmpty();
        assertThat(repository.countByFilter(filter)).isEqualTo(5);
    }

    @Test
    void byFilterPage_firstPageEqualsCursorFirstPage_sameParams_regressionAnchor() {
        // Arrange：25 条同 Job → 回归锚点：同参数页码第 1 页与游标首页一致（§3.5 两模式同序同过滤）
        for (int i = 1; i <= 25; i++) {
            saveFinished("PolicyFetchJob", JobExecutionStatus.SUCCESS);
        }

        // Act
        List<JobExecutionLog> pagedFirst =
                repository.byFilterPage(new JobLogFilter("PolicyFetchJob", null), 1, 20);
        List<JobExecutionLog> cursorFirst = repository.byJobNameCursor("PolicyFetchJob", null, 20);

        // Assert：逐条 id 一致（newest-first 同序）——两模式共存互不干扰
        assertThat(pagedFirst)
                .extracting(JobExecutionLog::getId)
                .containsExactlyElementsOf(
                        cursorFirst.stream().map(JobExecutionLog::getId).toList());
    }

    @Test
    void byFilterPage_deepPage_over100Rows_lastPagePartial() {
        // Arrange：103 条（5 整页 size=20 + 末页 3 条），对齐 job_execution_log 增长型深分页场景（ADR-0035）
        for (int i = 1; i <= 103; i++) {
            repository.save(JobExecutionLog.create("PolicyFetchJob", T1));
        }
        JobLogFilter filter = new JobLogFilter(null, JobExecutionStatus.STARTED);

        // Act + Assert：total=103；第 6 页（末页）恰 3 条（最旧 3 条）；第 7 页空
        assertThat(repository.countByFilter(filter)).isEqualTo(103);
        List<JobExecutionLog> lastPage = repository.byFilterPage(filter, 6, 20);
        assertThat(lastPage).hasSize(3);
        assertThat(lastPage.get(0).getId()).isGreaterThan(lastPage.get(2).getId());
        assertThat(repository.byFilterPage(filter, 7, 20)).isEmpty();
    }
}
