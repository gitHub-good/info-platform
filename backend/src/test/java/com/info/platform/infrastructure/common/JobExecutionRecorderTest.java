package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JobExecutionRecorder 单测（T33）：mock 仓储端口 + mock Clock，验证 start/success/failed 三步
 * 状态翻转与持久化调用，及「记录失败不阻断 Job」（start 抛异常返回 null、success/failed 见 null no-op）。
 *
 * <p>mock 边界 = 仓储端口（外部依赖）与时钟（可测时间），不 mock 被测类内部（对齐 04 测试规范）。
 */
class JobExecutionRecorderTest {

    private JobExecutionLogRepository repository;
    private Clock clock;
    private JobExecutionRecorder recorder;

    private static final Instant START = Instant.parse("2026-09-22T01:00:00Z");
    private static final Instant END = Instant.parse("2026-09-22T01:00:01Z");

    @BeforeEach
    void setUp() {
        repository = mock(JobExecutionLogRepository.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START, END);
        recorder = new JobExecutionRecorder(repository, clock);
        // repository.save 默认原样回传（带 id 模拟回填）
        when(repository.save(any(JobExecutionLog.class)))
                .thenAnswer(inv -> inv.getArgument(0, JobExecutionLog.class));
    }

    @Test
    void start_createsStartedRecord_persistsWithJobNameAndStartTime() {
        // Act
        JobExecutionLog log = recorder.start("PolicyFetchJob");

        // Assert：返回 STARTED 记录，jobName/startTime 正确，已落库
        assertThat(log).isNotNull();
        assertThat(log.getJobName()).isEqualTo("PolicyFetchJob");
        assertThat(log.getStartTime()).isEqualTo(START);
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.STARTED);
        assertThat(log.getEndTime()).isEmpty();
        verify(repository, times(1)).save(any(JobExecutionLog.class));
    }

    @Test
    void success_flipsToSuccess_setsEndTimeDurationAndCounts_persists() {
        // Arrange：先 start（消耗 START），返回的记录带 startTime
        JobExecutionLog log = recorder.start("PolicyFetchJob");
        // 模拟仓储回填 id（保存时回填主键）
        org.mockito.Mockito.reset(repository);
        when(repository.save(any(JobExecutionLog.class)))
                .thenAnswer(inv -> inv.getArgument(0, JobExecutionLog.class));

        // Act
        recorder.success(log, 5, 1);

        // Assert：状态 SUCCESS，endTime=END，duration=1000ms，计数写入，已落库
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
        assertThat(log.getEndTime()).contains(END);
        assertThat(log.getDurationMillis()).contains(1000L);
        assertThat(log.getProcessedCount()).isEqualTo(5);
        assertThat(log.getErrorCount()).isEqualTo(1);
        assertThat(log.getErrorMessage()).isEmpty();
        verify(repository).save(any(JobExecutionLog.class));
    }

    @Test
    void failed_flipsToFailed_setsErrorMessageAndCounts_persists() {
        JobExecutionLog log = recorder.start("PolicyFetchJob");
        org.mockito.Mockito.reset(repository);
        when(repository.save(any(JobExecutionLog.class)))
                .thenAnswer(inv -> inv.getArgument(0, JobExecutionLog.class));

        recorder.failed(log, "java.lang.RuntimeException: boom", 3, 2);

        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(log.getErrorMessage().orElse("")).contains("boom");
        assertThat(log.getEndTime()).contains(END);
        assertThat(log.getDurationMillis()).contains(1000L);
        assertThat(log.getProcessedCount()).isEqualTo(3);
        assertThat(log.getErrorCount()).isEqualTo(2);
        verify(repository).save(any(JobExecutionLog.class));
    }

    @Test
    void success_withNullLog_noOps() {
        // Arrange：start 持久化失败时返回 null（save 抛异常被吞）
        when(clock.instant()).thenReturn(START);
        when(repository.save(any(JobExecutionLog.class)))
                .thenThrow(new RuntimeException("DB down"));
        JobExecutionLog log = recorder.start("PolicyFetchJob");
        // start 返回 null 且不抛
        assertThat(log).isNull();
        // start 已尝试一次 save（抛异常），重置交互历史后再验证后续 no-op
        org.mockito.Mockito.reset(repository);

        // Act：success/failed 见 null log → no-op，不再落库
        recorder.success(null, 0, 0);
        recorder.failed(null, "x", 0, 0);

        // Assert
        verify(repository, never()).save(any(JobExecutionLog.class));
    }

    @Test
    void success_persistenceFailure_doesNotThrow_doesNotFlipBack() {
        // Arrange：start 成功（返回带 id 记录），success 落库抛异常
        when(clock.instant()).thenReturn(START, END);
        JobExecutionLog log = recorder.start("PolicyFetchJob");
        org.mockito.Mockito.reset(repository);
        when(repository.save(any(JobExecutionLog.class)))
                .thenThrow(new RuntimeException("DB down"));

        // Act：不应抛（记录失败不阻断 Job）
        recorder.success(log, 1, 0);

        // Assert：log 内存状态仍翻转（SUCCESS），但未阻断调用方
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
    }

    @Test
    void clock_providesDeterministicTimeAcrossSteps() {
        // 固定时钟可重复：start=START、success=END，duration 可预期
        when(clock.instant()).thenReturn(START, END);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        JobExecutionLog log = recorder.start("DailyRecommendationJob");
        recorder.success(log, 0, 0);
        assertThat(log.getDurationMillis()).contains(1000L);
        verify(repository, times(2)).save(any(JobExecutionLog.class));
    }

    @Test
    void failed_persistenceFailure_doesNotThrow() {
        when(clock.instant()).thenReturn(START, END);
        JobExecutionLog log = recorder.start("PolicyFetchJob");
        org.mockito.Mockito.reset(repository);
        when(repository.save(any(JobExecutionLog.class)))
                .thenThrow(new RuntimeException("DB down"));

        recorder.failed(log, "boom", 0, 0);
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
    }
}
