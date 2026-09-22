package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.infrastructure.policy.PolicyFetchJob;
import java.time.Instant;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * JobExecutionAspect 单测（T33）：mock ProceedingJoinPoint + mock Recorder，直调 {@link
 * JobExecutionAspect#around} 验证「start→proceed→success / catch→failed」通知逻辑与 jobName 派生。
 *
 * <p>切点匹配与代理织入是声明式的（AOP auto-config 开启即生效），本用例聚焦通知行为本身——对齐 04 测试规范 「mock 边界（AspectJ 框架类型
 * PJP/Recorder）不 mock 被测类内部」。真实织入的端到端验证见 {@link JobExecutionAspectIntegrationTest}。
 */
class JobExecutionAspectTest {

    private JobExecutionRecorder recorder;
    private JobExecutionAspect aspect;

    @BeforeEach
    void setUp() {
        recorder = Mockito.mock(JobExecutionRecorder.class);
        aspect = new JobExecutionAspect(recorder);
    }

    private ProceedingJoinPoint pjpFor(Class<?> declaringType) {
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        Signature sig = Mockito.mock(Signature.class);
        when(sig.getDeclaringType()).thenReturn(declaringType);
        when(pjp.getSignature()).thenReturn(sig);
        return pjp;
    }

    @Test
    void around_success_recordsStartedThenSuccess() throws Throwable {
        // Arrange：start 返回 STARTED 记录；proceed 正常返回
        JobExecutionLog started =
                JobExecutionLog.create("PolicyFetchJob", Instant.parse("2026-09-22T01:00:00Z"));
        when(recorder.start("PolicyFetchJob")).thenReturn(started);
        ProceedingJoinPoint pjp = pjpFor(PolicyFetchJob.class);
        when(pjp.proceed()).thenReturn(null);

        // Act
        Object result = aspect.around(pjp);

        // Assert：start→proceed→success，未记 failed
        verify(recorder).start("PolicyFetchJob");
        verify(pjp).proceed();
        verify(recorder).success(eq(started), eq(0), eq(0));
        verify(recorder, never()).failed(any(), any(), anyInt(), anyInt());
        assertThat(result).isNull();
    }

    @Test
    void around_proceedReturnsValue_forwardsReturnValue() throws Throwable {
        Object payload = new Object();
        when(recorder.start("PolicyFetchJob"))
                .thenReturn(JobExecutionLog.create("PolicyFetchJob", Instant.now()));
        ProceedingJoinPoint pjp = pjpFor(PolicyFetchJob.class);
        when(pjp.proceed()).thenReturn(payload);

        assertThat(aspect.around(pjp)).isSameAs(payload);
    }

    @Test
    void around_proceedThrows_recordsFailedAndRethrows() throws Throwable {
        // Arrange
        JobExecutionLog started =
                JobExecutionLog.create("PolicyFetchJob", Instant.parse("2026-09-22T01:00:00Z"));
        when(recorder.start("PolicyFetchJob")).thenReturn(started);
        RuntimeException boom = new RuntimeException("boom");
        ProceedingJoinPoint pjp = pjpFor(PolicyFetchJob.class);
        when(pjp.proceed()).thenThrow(boom);

        // Act + Assert：异常原样上抛，先记 failed（摘要含 boom），不记 success
        assertThatThrownBy(() -> aspect.around(pjp)).isSameAs(boom);
        verify(recorder).start("PolicyFetchJob");
        verify(recorder).failed(eq(started), contains("boom"), eq(0), eq(0));
        verify(recorder, never()).success(any(), anyInt(), anyInt());
    }

    @Test
    void around_jobNameDerivedFromDeclaringTypeSimple() throws Throwable {
        // 用另一个 Job 类验证 jobName = 类简单名（而非方法名）
        when(recorder.start("PushRetryJob"))
                .thenReturn(JobExecutionLog.create("PushRetryJob", Instant.now()));
        ProceedingJoinPoint pjp = pjpFor(com.info.platform.application.push.PushRetryJob.class);
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp);

        verify(recorder).start("PushRetryJob");
    }

    @Test
    void around_startReturnsNull_recorderSkippedButProceedStillRuns() throws Throwable {
        // Arrange：recorder.start 返回 null（持久化失败兜底）
        when(recorder.start(any())).thenReturn(null);
        ProceedingJoinPoint pjp = pjpFor(PolicyFetchJob.class);
        when(pjp.proceed()).thenReturn("ok");

        // Act：Job 业务仍正常执行，不被留痕链路阻断
        assertThat(aspect.around(pjp)).isEqualTo("ok");

        // Assert：proceed 已调；success/failed 见 null no-op
        verify(pjp).proceed();
        verify(recorder, never()).success(any(), anyInt(), anyInt());
        verify(recorder, never()).failed(any(), any(), anyInt(), anyInt());
    }

    @Test
    void around_proceedThrowsAfterNullStart_noFailedSideEffect() throws Throwable {
        when(recorder.start(any())).thenReturn(null);
        ProceedingJoinPoint pjp = pjpFor(PolicyFetchJob.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("x"));

        assertThatThrownBy(() -> aspect.around(pjp)).isInstanceOf(RuntimeException.class);
        verify(recorder, never()).failed(any(), any(), anyInt(), anyInt());
    }

    // —— helpers ——
    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains(fragment));
    }
}
