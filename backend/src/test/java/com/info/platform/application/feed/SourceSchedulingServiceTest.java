package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SourceSchedulingService 单测（T103，ADR-0040）：到期判定（next_due/backoff max 语义）/ 重启抖动重铺 / 全局并发 4 上限 /
 * inFlight 防重派 / tick 预算超时不悬挂 / 手动轮询 30074。fake 仓储 + 受控线程池，零 DB 零外呼。
 */
class SourceSchedulingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InfoSourceRepository infoSourceRepository = mock(InfoSourceRepository.class);
    private final SourcePollStateRepository stateRepository = mock(SourcePollStateRepository.class);
    private final FeedIngestService ingestService = mock(FeedIngestService.class);
    private final ExecutorService controlPool = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        controlPool.shutdownNow();
    }

    private static InfoSource source(long id, int intervalMinutes) {
        InfoSource source =
                InfoSource.create(
                        "t103_src_" + id,
                        "源" + id,
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + id,
                        SourceConfig.empty(),
                        intervalMinutes,
                        true,
                        false);
        source.assignPersisted(id, NOW, NOW);
        return source;
    }

    private static SourcePollState state(long sourceId, Instant nextDue, Instant backoffUntil) {
        return new SourcePollState(
                sourceId,
                null,
                null,
                nextDue,
                null,
                null,
                0,
                backoffUntil,
                null,
                null,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600));
    }

    private SourceSchedulingService service(ExecutorService pool) {
        return new SourceSchedulingService(
                infoSourceRepository,
                stateRepository,
                ingestService,
                CLOCK,
                pool,
                new java.util.Random(42),
                Duration.ofSeconds(45));
    }

    @Test
    void tick_dispatchesDue_skipsFutureNextDueAndBackoffSilence() throws Exception {
        InfoSource due = source(1, 5);
        InfoSource future = source(2, 5);
        InfoSource backoff = source(3, 5);
        when(infoSourceRepository.findActive()).thenReturn(List.of(due, future, backoff));
        when(stateRepository.findBySourceId(1L))
                .thenReturn(java.util.Optional.of(state(1L, NOW.minusSeconds(60), null)));
        // 未来到期：不派发
        when(stateRepository.findBySourceId(2L))
                .thenReturn(java.util.Optional.of(state(2L, NOW.plusSeconds(60), null)));
        // next_due 已过但退避中：max(next_due, backoff_until) 未到 → 不派发
        when(stateRepository.findBySourceId(3L))
                .thenReturn(
                        java.util.Optional.of(
                                state(3L, NOW.minusSeconds(60), NOW.plusSeconds(300))));

        SourceSchedulingService.TickReport report = service(controlPool).tick();

        assertThat(report.dispatched()).isEqualTo(1);
        // 抖动重铺不触发（仅落后一个滑差，非停机积压）
        verify(stateRepository, never()).update(any());
    }

    @Test
    void tick_missingStateRow_treatedAsDueImmediately() throws Exception {
        InfoSource fresh = source(9, 5);
        when(infoSourceRepository.findActive()).thenReturn(List.of(fresh));
        when(stateRepository.findBySourceId(9L)).thenReturn(java.util.Optional.empty());
        when(ingestService.poll(any(InfoSource.class))).thenReturn(true);

        SourceSchedulingService.TickReport report = service(controlPool).tick();

        assertThat(report.dispatched()).isEqualTo(1);
        assertThat(report.ok()).isEqualTo(1);
    }

    @Test
    void tick_overdueBeyondInterval_relayedWithJitterWithinCap() throws Exception {
        InfoSource stale = source(4, 5);
        when(infoSourceRepository.findActive()).thenReturn(List.of(stale));
        // next_due 落后 2 个间隔（重启积压）→ 重铺 [now, now+min(5min,5min))，本 tick 不抓
        when(stateRepository.findBySourceId(4L))
                .thenReturn(java.util.Optional.of(state(4L, NOW.minusSeconds(600), null)));

        SourceSchedulingService.TickReport report = service(controlPool).tick();

        assertThat(report.dispatched()).isZero();
        ArgumentCaptor<SourcePollState> relayed = ArgumentCaptor.forClass(SourcePollState.class);
        verify(stateRepository).update(relayed.capture());
        Instant relayDue = relayed.getValue().nextDueAt();
        assertThat(relayDue).isAfterOrEqualTo(NOW);
        assertThat(relayDue).isBeforeOrEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(ingestService, never()).poll(any(InfoSource.class));
    }

    @Test
    void tick_globalConcurrencyCappedAtFour() throws Exception {
        List<InfoSource> eight = new java.util.ArrayList<>();
        for (long id = 10; id < 18; id++) {
            eight.add(source(id, 30));
            when(stateRepository.findBySourceId(id))
                    .thenReturn(java.util.Optional.of(state(id, NOW.minusSeconds(30), null)));
        }
        when(infoSourceRepository.findActive()).thenReturn(eight);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Long> polled = ConcurrentHashMap.newKeySet();
        when(ingestService.poll(any(InfoSource.class)))
                .thenAnswer(
                        invocation -> {
                            polled.add(invocation.getArgument(0, InfoSource.class).getId());
                            int now = current.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            release.await();
                            current.decrementAndGet();
                            return true;
                        });

        var tickerResult =
                new Object() {
                    volatile SourceSchedulingService.TickReport report;
                };
        Thread ticker = new Thread(() -> tickerResult.report = service(controlPool).tick());
        ticker.start();

        // 并发收敛到 4（上限），其余 4 个不派发
        awaitUntil(() -> polled.size() >= 4);
        Thread.sleep(200);
        assertThat(peak.get()).isLessThanOrEqualTo(SourceSchedulingService.MAX_CONCURRENT_POLLS);
        assertThat(polled.size()).isEqualTo(SourceSchedulingService.MAX_CONCURRENT_POLLS);
        release.countDown();
        ticker.join(5000);
        assertThat(tickerResult.report.dispatched()).isEqualTo(4);
        assertThat(tickerResult.report.ok()).isEqualTo(4);
    }

    @Test
    void tick_sourceInFlight_notRedispatchedNextTick() throws Exception {
        InfoSource busy = source(20, 5);
        when(infoSourceRepository.findActive()).thenReturn(List.of(busy));
        when(stateRepository.findBySourceId(20L))
                .thenReturn(java.util.Optional.of(state(20L, NOW.minusSeconds(30), null)));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        when(ingestService.poll(any(InfoSource.class)))
                .thenAnswer(
                        inv -> {
                            started.incrementAndGet();
                            release.await();
                            return true;
                        });
        // 同一服务实例（生产单例）：第一 tick 占用在飞集合，第二 tick 不得重派
        SourceSchedulingService service = service(controlPool);

        Thread ticker = new Thread(service::tick);
        ticker.start();
        awaitUntil(() -> started.get() >= 1);
        // 第二 tick：该源在飞（inFlight）→ 零派发（tick 不等待，直接返回）
        SourceSchedulingService.TickReport second = service.tick();
        assertThat(second.dispatched()).isZero();
        release.countDown();
        ticker.join(5000);
    }

    @Test
    void tick_budgetExhausted_returnsWithoutHanging_inFlightStillGuarded() throws Exception {
        InfoSource slow = source(30, 5);
        when(infoSourceRepository.findActive()).thenReturn(List.of(slow));
        when(stateRepository.findBySourceId(30L))
                .thenReturn(java.util.Optional.of(state(30L, NOW.minusSeconds(30), null)));
        CountDownLatch release = new CountDownLatch(1);
        when(ingestService.poll(any(InfoSource.class)))
                .thenAnswer(
                        inv -> {
                            release.await();
                            return true;
                        });
        SourceSchedulingService budgeted =
                new SourceSchedulingService(
                        infoSourceRepository,
                        stateRepository,
                        ingestService,
                        CLOCK,
                        Executors.newFixedThreadPool(1),
                        new java.util.Random(42),
                        Duration.ofMillis(150));

        SourceSchedulingService.TickReport report = budgeted.tick();

        // 预算耗尽：已派发 1 但未收编结果；在飞集合兜底（下一 tick 不重派）
        assertThat(report.dispatched()).isEqualTo(1);
        assertThat(report.ok() + report.fail()).isEqualTo(0);
        release.countDown();
    }

    @Test
    void pollNow_inFlightSource_rejected409() throws Exception {
        InfoSource target = source(40, 5);
        CountDownLatch release = new CountDownLatch(1);
        when(ingestService.poll(any(InfoSource.class)))
                .thenAnswer(
                        inv -> {
                            release.await();
                            return true;
                        });
        SourceSchedulingService service = service(controlPool);

        Thread first = new Thread(() -> service.pollNow(target));
        first.start();
        awaitUntil(() -> Thread.State.WAITING.equals(first.getState()));

        assertThatThrownBy(() -> service.pollNow(target))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_POLL_IN_FLIGHT);
        release.countDown();
        first.join(5000);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("等待条件超时");
            }
            Thread.sleep(20);
        }
    }
}
