package com.info.platform.infrastructure.jobrun;

import com.info.platform.application.common.RuntimeConfigChangedEvent;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.JobScheduleSettings;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * 集中调度中心（T37，基础设施层，方案 §4.5 / ADR-0017 冲突解法 2）。
 *
 * <p>替代散落的 {@code @Scheduled}：按 {@code job.{JOB_KEY}} 运行时配置在自有 {@link ThreadPoolTaskScheduler}
 * 上注册（FIXED_DELAY 用 {@code scheduleWithFixedDelay}——首跑立即、上轮结束后间隔，对齐原 @Scheduled fixedDelay 语义；CRON
 * 用 {@link CronTrigger}）。测试 profile 种子 {@code enabled=false} → 零注册， @Scheduled 时代的测试隔离语义等价平移。
 *
 * <h2>热切换（ADR-0017：自下一调度周期生效）</h2>
 *
 * 订阅 {@link RuntimeConfigChangedEvent}：job 域键变更即取消旧 future（{@code cancel(false)} 不中断进行中的一轮）
 * 并按新配置重注册——启停立即，间隔/cron 变更对后续周期生效；非法 cron 已在写路径校验拦截，此处兜底捕获记 WARN（存量种子损坏时不注册、不阻断启动）。
 *
 * <h2>启动时序</h2>
 *
 * 监听 {@link ApplicationReadyEvent} 且 {@code @Order(100)} 晚于配置中心种子导入（其监听为 @Order(0)）——
 * 首启即以种子值为准注册，避免「种子前按代码缺省误注册」。
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    /** 调度线程数：每任务一线程上限（个人量级 8 任务——M13 增 SOURCE_POLL tick 长驻占用 1 线程， 5→8 防与既有 7 Job 抢占，ADR-0040）。 */
    private static final int POOL_SIZE = 8;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final JobRegistry registry;
    private final JobExecutor executor;
    private final RuntimeConfigService configService;
    private final Clock clock;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public JobScheduler(
            JobRegistry registry,
            JobExecutor executor,
            RuntimeConfigService configService,
            Clock clock) {
        this.registry = registry;
        this.executor = executor;
        this.configService = configService;
        this.clock = clock;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(POOL_SIZE);
        taskScheduler.setThreadNamePrefix("job-sched-");
        taskScheduler.setDaemon(true);
        taskScheduler.setRemoveOnCancelPolicy(true);
        taskScheduler.initialize();
    }

    /** 启动就绪（晚于配置中心种子导入）：按当前配置注册全部任务。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onApplicationReady() {
        int enabled = 0;
        for (ManagedJob job : registry.jobs()) {
            enabled += reschedule(job) ? 1 : 0;
        }
        log.info("任务调度中心就绪：注册 {} 个任务（共 {} 个，未注册=停用或配置非法）", enabled, registry.jobs().size());
    }

    /** 配置热切换：job 域键变更 → 取消旧调度按新值重注册（下一周期生效，进行中一轮跑完）。 */
    @EventListener
    public void onRuntimeConfigChanged(RuntimeConfigChangedEvent event) {
        String key = event.configKey();
        if (key == null || !key.startsWith(JobScheduleSettings.KEY_PREFIX)) {
            return;
        }
        String jobKey = key.substring(JobScheduleSettings.KEY_PREFIX.length());
        registry.byJobKey(jobKey).ifPresent(this::reschedule);
    }

    /**
     * 按当前运行时配置重注册单个任务（synchronized 防同任务并发重注册交错）。
     *
     * @return true = 已注册（enabled 且参数可解析）
     */
    synchronized boolean reschedule(ManagedJob job) {
        cancel(job.jobKey());
        JobScheduleSettings settings = settingsOf(job);
        if (!settings.enabled()) {
            return false;
        }
        try {
            if (job.scheduleType() == ScheduleType.CRON) {
                futures.put(
                        job.jobKey(),
                        taskScheduler.schedule(
                                () -> executor.runScheduled(job),
                                new CronTrigger(settings.cron())));
            } else {
                if (settings.intervalMillis() <= 0) {
                    log.warn("任务 {} 间隔非法（{}ms），不注册", job.jobKey(), settings.intervalMillis());
                    return false;
                }
                futures.put(
                        job.jobKey(),
                        taskScheduler.scheduleWithFixedDelay(
                                () -> executor.runScheduled(job),
                                Duration.ofMillis(settings.intervalMillis())));
            }
            log.info(
                    "任务已注册 jobKey={} type={} enabled 下次执行约 {}",
                    job.jobKey(),
                    job.scheduleType(),
                    nextExecutionTime(job.jobKey()).map(Instant::toString).orElse("-"));
            return true;
        } catch (IllegalArgumentException e) {
            // 写路径已校验拦截，此处兜底（存量种子损坏）：不注册、不阻断
            log.warn("任务 {} 调度参数非法（{}），不注册: {}", job.jobKey(), describe(settings), e.getMessage());
            futures.remove(job.jobKey());
            return false;
        }
    }

    /** 下次执行时间（停用/未注册返回空；对 FIXED_DELAY 与 CronTrigger 均按 future 剩余时延推算）。 */
    public Optional<Instant> nextExecutionTime(String jobKey) {
        ScheduledFuture<?> future = futures.get(jobKey);
        if (future == null) {
            return Optional.empty();
        }
        long delayMillis = future.getDelay(TimeUnit.MILLISECONDS);
        return Optional.of(clock.instant().plusMillis(Math.max(0, delayMillis)));
    }

    @PreDestroy
    void shutdown() {
        futures.values().forEach(future -> future.cancel(false));
        taskScheduler.shutdown();
    }

    private void cancel(String jobKey) {
        ScheduledFuture<?> previous = futures.remove(jobKey);
        if (previous != null) {
            // cancel(false)：不中断进行中的一轮（ADR-0017「进行中一轮跑完」）
            previous.cancel(false);
        }
    }

    private JobScheduleSettings settingsOf(ManagedJob job) {
        return configService
                .read(JobScheduleSettings.KEY_PREFIX + job.jobKey())
                .map(RuntimeConfigEntry::document)
                .map(JobScheduleSettings::from)
                .orElse(JobScheduleSettings.DISABLED);
    }

    private static String describe(JobScheduleSettings settings) {
        return "interval=" + settings.intervalMillis() + "ms, cron=" + settings.cron();
    }
}
