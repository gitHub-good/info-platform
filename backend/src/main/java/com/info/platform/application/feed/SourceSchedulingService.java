package com.info.platform.application.feed;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SOURCE_POLL tick 调度服务（M13 T103，ADR-0040）：每 tick 现读启用源（配置热生效）→ 到期计算（now ≥ max(next_due,
 * backoff_until)）→ 重启抖动重铺（停机积压源随机散开防齐发）→ 有界线程池并发派发（全局上限 4）→ 单源在飞集合防重派 → 预算内等待收编（45s，超时不中断在飞轮——下
 * tick 由 inFlight 兜底）。
 */
@Component
public class SourceSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SourceSchedulingService.class);

    /** 全局并发上限（代码常量，ADR-0040）。 */
    public static final int MAX_CONCURRENT_POLLS = 4;

    /** tick 等待预算：超时不中断在飞轮（在飞集合兜底防重派）。 */
    public static final Duration TICK_BUDGET = Duration.ofSeconds(45);

    /** 重启抖动重铺上限（min(interval, 5min) 的 5min 半径）。 */
    public static final Duration MAX_RESTART_JITTER = Duration.ofMinutes(5);

    private final InfoSourceRepository infoSourceRepository;
    private final SourcePollStateRepository stateRepository;
    private final FeedIngestService ingestService;
    private final Clock clock;
    private final ExecutorService pollPool;
    private final Random jitter;
    private final Duration tickBudget;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 生产装配：固定 4 线程守护池（每 tick 派发单源轮次）。 */
    @Autowired
    public SourceSchedulingService(
            InfoSourceRepository infoSourceRepository,
            SourcePollStateRepository stateRepository,
            FeedIngestService ingestService,
            Clock clock) {
        this(
                infoSourceRepository,
                stateRepository,
                ingestService,
                clock,
                Executors.newFixedThreadPool(MAX_CONCURRENT_POLLS, daemonFactory()),
                new Random(),
                TICK_BUDGET);
    }

    /** 全参构造（单测注入受控线程池/随机源/预算）。 */
    SourceSchedulingService(
            InfoSourceRepository infoSourceRepository,
            SourcePollStateRepository stateRepository,
            FeedIngestService ingestService,
            Clock clock,
            ExecutorService pollPool,
            Random jitter,
            Duration tickBudget) {
        this.infoSourceRepository = infoSourceRepository;
        this.stateRepository = stateRepository;
        this.ingestService = ingestService;
        this.clock = clock;
        this.pollPool = pollPool;
        this.jitter = jitter;
        this.tickBudget = tickBudget;
    }

    /** 一个 tick：现读启用源 → 到期计算 → 并发派发（并发余量内，最久到期优先）→ 预算内等待收编。 */
    public TickReport tick() {
        Instant now = clock.instant();
        List<DueSource> due = collectDue(now);
        int slots = MAX_CONCURRENT_POLLS - inFlight.size();
        if (slots <= 0 || due.isEmpty()) {
            return new TickReport(0, 0, 0);
        }
        due.sort(Comparator.comparing(DueSource::dueAt));
        List<DueSource> targets = due.subList(0, Math.min(slots, due.size()));
        int dispatched = 0;
        List<Future<Boolean>> futures = new ArrayList<>(targets.size());
        for (DueSource target : targets) {
            if (!inFlight.add(target.source().getId())) {
                continue; // 单源在飞防重派（手动轮询与定时并发同守卫）
            }
            dispatched++;
            futures.add(dispatch(target.source()));
        }
        Completion completion = awaitCompletion(futures);
        return new TickReport(dispatched, completion.ok(), completion.fail());
    }

    /**
     * 手动抓取（T105 端点同通道：同去重/同游标）：单源 CAS 守卫。
     *
     * @throws BusinessException 30074 该源抓取进行中
     */
    public boolean pollNow(InfoSource source) {
        if (!inFlight.add(source.getId())) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_POLL_IN_FLIGHT, "该源抓取正在进行中: " + source.getSourceCode());
        }
        try {
            return ingestService.poll(source);
        } finally {
            inFlight.remove(source.getId());
        }
    }

    /**
     * 手动抓取异步受理（T105 POST /{id}/poll 202）：CAS 守卫同步判 30074，轮次派发域内线程池（方案库 12：手动受理非 JobExecutor 通道，留痕走
     * data_source_event）。
     *
     * @throws BusinessException 30074 该源抓取进行中
     */
    public Future<Boolean> submitPollNow(InfoSource source) {
        if (!inFlight.add(source.getId())) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_POLL_IN_FLIGHT, "该源抓取正在进行中: " + source.getSourceCode());
        }
        boolean submitted = false;
        try {
            Future<Boolean> future = pollPool.submit(guardedPoll(source));
            submitted = true;
            return future;
        } finally {
            if (!submitted) {
                inFlight.remove(source.getId());
            }
        }
    }

    /** 到期筛选 + 重启抖动重铺（仅停机积压源：next_due 落后超过一个完整间隔）。 */
    private List<DueSource> collectDue(Instant now) {
        List<DueSource> due = new ArrayList<>();
        for (InfoSource source : infoSourceRepository.findActive()) {
            SourcePollState state = stateRepository.findBySourceId(source.getId()).orElse(null);
            if (!isDue(now, state)) {
                continue;
            }
            if (relayIfRestartBacklog(now, source, state)) {
                continue;
            }
            due.add(new DueSource(source, state == null ? now : state.nextDueAt()));
        }
        return due;
    }

    /** 到期判定：now ≥ max(next_due_at, backoff_until)；无状态行 = 新源立即到期。 */
    private static boolean isDue(Instant now, SourcePollState state) {
        if (state == null) {
            return true;
        }
        Instant effective =
                state.backoffUntil() != null && state.backoffUntil().isAfter(state.nextDueAt())
                        ? state.backoffUntil()
                        : state.nextDueAt();
        return !now.isBefore(effective);
    }

    /**
     * 重启抖动重铺：next_due 落后超过一个完整间隔 = 停机/重启积压（正常调度滑差小于间隔）——随机延迟 [0, min(interval, 5min))
     * 重铺，防重启后齐发（ADR-0040）。重铺过的源本 tick 不抓（下 tick 按 new next_due 到期）。
     */
    private boolean relayIfRestartBacklog(Instant now, InfoSource source, SourcePollState state) {
        if (state == null) {
            return false;
        }
        Duration interval = Duration.ofMinutes(source.getIntervalMinutes());
        if (!state.nextDueAt().isBefore(now.minus(interval))) {
            return false;
        }
        long capSeconds = Math.min(interval.toSeconds(), MAX_RESTART_JITTER.toSeconds());
        Instant relay = now.plusSeconds(jitter.nextLong(capSeconds + 1));
        stateRepository.update(
                new SourcePollState(
                        state.sourceId(),
                        state.lastAttemptAt(),
                        state.lastSuccessAt(),
                        relay,
                        state.cursorValue(),
                        state.cursorUpdatedAt(),
                        state.consecutiveFailures(),
                        state.backoffUntil(),
                        state.lastDurationMillis(),
                        state.lastRoundDetail(),
                        state.lastError(),
                        state.createdAt(),
                        now));
        log.info("重启积压源抖动重铺 source={} nextDueAt={}", source.getSourceCode(), relay);
        return true;
    }

    private Future<Boolean> dispatch(InfoSource source) {
        return pollPool.submit(guardedPoll(source));
    }

    /** 单源一轮的可提交体：轮次结束（无论成败）自清在飞集合。 */
    private java.util.concurrent.Callable<Boolean> guardedPoll(InfoSource source) {
        return () -> {
            try {
                return ingestService.poll(source);
            } finally {
                inFlight.remove(source.getId());
            }
        };
    }

    /** 预算内等待收编：逐 future 以剩余预算限时 get；超时放弃收编（不中断在飞轮，dispatch 的 finally 自清在飞集合）。 */
    private Completion awaitCompletion(List<Future<Boolean>> futures) {
        long deadlineNanos = System.nanoTime() + tickBudget.toNanos();
        int ok = 0;
        int fail = 0;
        for (Future<Boolean> future : futures) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                log.warn("tick 预算耗尽（{}ms），在飞轮不中断由在飞集合兜底", tickBudget.toMillis());
                break;
            }
            try {
                if (future.get(remaining, TimeUnit.NANOSECONDS)) {
                    ok++;
                } else {
                    fail++;
                }
            } catch (TimeoutException e) {
                log.warn("tick 预算耗尽（{}ms），在飞轮不中断由在飞集合兜底", tickBudget.toMillis());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("tick 等待被中断，剩余在飞轮由在飞集合兜底");
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                // poll 契约不外抛，此为防御性兜底
                fail++;
            }
        }
        return new Completion(ok, fail);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "source-poll-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        pollPool.shutdownNow();
    }

    /** tick 摘要（JobRunStats 明细源：dispatched/ok/fail 段式）。 */
    public record TickReport(int dispatched, int ok, int fail) {

        /** ADR-0036 段式惯例。 */
        public String detail() {
            return "dispatched=" + dispatched + "; ok=" + ok + "; fail=" + fail;
        }
    }

    private record DueSource(InfoSource source, Instant dueAt) {}

    private record Completion(int ok, int fail) {}
}
