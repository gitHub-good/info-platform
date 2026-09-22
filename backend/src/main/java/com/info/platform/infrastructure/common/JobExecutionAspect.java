package com.info.platform.infrastructure.common;

import com.info.platform.domain.common.JobExecutionLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job 执行留痕 AOP 切面（基础设施层横切，T33）。
 *
 * <p>{@code @Around} 拦截各 {@link Component} Bean 上 {@link Scheduled @Scheduled} 标注的方法，经 {@link
 * JobExecutionRecorder} 记录每次执行的 STARTED→（SUCCESS|FAILED），无需各 Job 改造——横切一处即可覆盖现有 5 个 Job 与未来新增 Job：
 *
 * <ul>
 *   <li>{@code PolicyFetchJob#fetch}（政策抓取）
 *   <li>{@code AnomalyDetectionJob#detect}（异动检测）
 *   <li>{@code PushRetryJob#retry}（补推）
 *   <li>{@code PolicyTendencyJob#judgePending}（政策倾向）
 *   <li>{@code DailyRecommendationJob#prefetchDaily}（每日推荐预热）
 * </ul>
 *
 * <h2>切点</h2>
 *
 * {@code @within(Component) && @annotation(Scheduled)}——Bean 标 {@code @Component} 且方法标 {@code @Scheduled}。
 * {@code @within(Component)} 使 ClassFilter 命中 {@code @Component} 类型、确保 Bean 被代理织入； {@code
 * @annotation(Scheduled)} 限定到 {@code @Scheduled} 轮询入口。精准命中 5 个 Job 入口，不误伤普通方法。
 *
 * <h2>状态语义</h2>
 *
 * <ul>
 *   <li>proceed 前调 {@link JobExecutionRecorder#start} 记 STARTED。
 *   <li>proceed 正常返回 → {@link JobExecutionRecorder#success}（SUCCESS）。Job 内部 try-catch 吞掉的异常
 *       不会上抛，方法正常返回即记成功——SUCCESS 语义=「整轮执行完成未抛异常」。
 *   <li>proceed 抛异常 → {@link JobExecutionRecorder#failed}（FAILED，记异常摘要）并<b>原样上抛</b>（调度无调用方，
 *       上抛不影响业务；但保留异常传播契约，未来调用方接入时可见）。
 * </ul>
 *
 * <p>{@code processedCount/errorCount} 首期 AOP 拿不到 Job 内部统计，固定记 0；各 Job 可选显式调 {@link
 * JobExecutionRecorder#success} 补充（增强，非必需）。jobName 派生自 {@link
 * org.aspectj.lang.Signature#getDeclaringType 方法声明类的简单名}（如 "PolicyFetchJob"）。
 *
 * <p>测试不依赖真实调度：@Scheduled 在测试 profile 受 {@code *.enabled=false} 开关关闭、不装配不触发；单测直调 Job 方法（或测试夹具
 * Job）经代理触发本切面验证记录（对齐 04 测试规范）。
 */
@Aspect
@Component
public class JobExecutionAspect {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionAspect.class);

    private final JobExecutionRecorder recorder;

    public JobExecutionAspect(JobExecutionRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 切点：@Component Bean 上 @Scheduled 标注的方法。
     *
     * <p>注解类型用<b>全限定名</b>——AspectJ 切点表达式不读 Java import，按类路径解析简单名会误匹配非注解类型 （如 {@code Component} 解析到
     * {@code java.awt.Component} → {@code IllegalArgumentException: Type referred to is not an
     * annotation type}，切点被拒、通知不织入）。全限定名消除歧义。
     */
    @Pointcut(
            "@within(org.springframework.stereotype.Component) && "
                    + "@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public void scheduledJobMethod() {}

    /**
     * 环绕通知：记录 Job 执行 STARTED→（SUCCESS|FAILED）。
     *
     * <p>记录链路任何异常均被 {@link JobExecutionRecorder} 内部 catch，绝不阻断 Job 业务。
     */
    @Around("scheduledJobMethod()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String jobName = pjp.getSignature().getDeclaringType().getSimpleName();
        JobExecutionLog logEntry = recorder.start(jobName);
        try {
            Object result = pjp.proceed();
            if (logEntry != null) {
                recorder.success(logEntry, 0, 0);
            }
            return result;
        } catch (Throwable ex) {
            if (logEntry != null) {
                recorder.failed(logEntry, ex.toString(), 0, 0);
            }
            throw ex;
        }
    }
}
