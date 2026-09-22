package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * JobExecutionAspect 织入集成测试（T33）：证明切点匹配 + AOP 代理在真实 Spring 上下文中生效。
 *
 * <p>样本 Job（{@link SampleSuccessScheduledJob} / {@link SampleFailingScheduledJob}）为
 * {@code @Component} + {@code @Scheduled}（与真实 Job 同形态），被 {@code @within(Component)
 * && @annotation(Scheduled)} 切点命中 → CGLIB 代理 → 直调 Job 方法经代理触发 {@code @Around} 记录。测试 profile 无
 * {@code @EnableScheduling}， 调度器不自动触发 {@code run()}，仅本测试显式调用验证记录（对齐 04 测试规范「不依赖真实调度」）。
 *
 * <p>真实 {@link JobExecutionRecorder} + 真实仓储（SQLite 内存库，Flyway V12 建表）； {@code @Transactional}
 * 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobExecutionAspectIntegrationTest {

    @Autowired private SampleSuccessScheduledJob successJob;

    @Autowired private SampleFailingScheduledJob failingJob;

    @Autowired private JobExecutionLogRepository repository;

    @Test
    void callingScheduledMethod_recordsStartedThenSuccess() {
        // Act：直调经代理的 Job 方法 → @Around 拦截
        successJob.run();

        // Assert：落库一条 SUCCESS 记录，jobName=类简单名，endTime/duration 已填
        List<JobExecutionLog> logs =
                repository.byJobNameCursor("SampleSuccessScheduledJob", null, 10);
        assertThat(logs).hasSize(1);
        JobExecutionLog log = logs.get(0);
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
        assertThat(log.getJobName()).isEqualTo("SampleSuccessScheduledJob");
        assertThat(log.getStartTime()).isNotNull();
        assertThat(log.getEndTime()).isPresent();
        assertThat(log.getDurationMillis()).isPresent();
        assertThat(log.getErrorMessage()).isEmpty();
    }

    @Test
    void callingScheduledMethodThatThrows_recordsFailedAndRethrows() {
        // Act + Assert：异常原样上抛，落库一条 FAILED 记录（摘要含 sample-failure）
        assertThatThrownBy(failingJob::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sample-failure");

        List<JobExecutionLog> logs =
                repository.byJobNameCursor("SampleFailingScheduledJob", null, 10);
        assertThat(logs).hasSize(1);
        JobExecutionLog log = logs.get(0);
        assertThat(log.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(log.getErrorMessage().orElse("")).contains("sample-failure");
        assertThat(log.getEndTime()).isPresent();
        assertThat(log.getDurationMillis()).isPresent();
    }
}
