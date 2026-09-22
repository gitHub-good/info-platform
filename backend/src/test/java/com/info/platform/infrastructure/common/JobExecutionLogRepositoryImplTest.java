package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
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
}
