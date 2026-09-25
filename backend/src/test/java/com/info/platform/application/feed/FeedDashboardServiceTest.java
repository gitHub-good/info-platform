package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFailureEvent;
import com.info.platform.domain.feed.FeedFailureEventRepository;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * FeedDashboardService 单元测试（T114，REQ 故事 2）：mock 全部仓储端口 + 固定时钟——
 * 聚合口径（对账一致/活跃失败源判定/感知延迟增量轮口径/排序置顶/失败列表组装）与空数据。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TODAY = "2026-09-22"; // Asia/Shanghai 本地日
    private static final Instant TODAY_START =
            LocalDate.parse(TODAY).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();

    @Mock private InfoSourceRepository infoSourceRepository;
    @Mock private SourcePollStateRepository stateRepository;
    @Mock private SourceDailyStatsRepository statsRepository;
    @Mock private FeedItemRepository itemRepository;
    @Mock private FeedFailureEventRepository failureEventRepository;

    private FeedDashboardService service;

    @BeforeEach
    void setUp() {
        service =
                new FeedDashboardService(
                        infoSourceRepository,
                        stateRepository,
                        statsRepository,
                        itemRepository,
                        failureEventRepository,
                        CLOCK);
        // 缺省桩：空数据（各用例按需覆写）
        when(infoSourceRepository.findAll()).thenReturn(List.of());
        when(statsRepository.findSince(TODAY)).thenReturn(List.of());
        when(itemRepository.countGroupedBySource()).thenReturn(Map.of());
        when(itemRepository.findFirstIngestAt()).thenReturn(Map.of());
        when(itemRepository.fetchLatencySamplesSince(TODAY_START.toString())).thenReturn(List.of());
        when(failureEventRepository.findRecent(FeedDashboardService.FAILURE_LIST_LIMIT))
                .thenReturn(List.of());
    }

    // —— 对账一致（REQ 场景 2） ——

    @Test
    void reconciliation_globalTodayEqualsSumOfRowsAndActiveFailedCounts() {
        InfoSource okSrc = source(1, "t114_a", true);
        InfoSource allFailSrc = source(2, "t114_b", true);
        InfoSource noRoundSrc = source(3, "t114_c", true);
        InfoSource disabledSrc = source(4, "t114_d", false);
        InfoSource backoffSrc = source(5, "t114_e", true);
        when(infoSourceRepository.findAll())
                .thenReturn(List.of(okSrc, allFailSrc, noRoundSrc, disabledSrc, backoffSrc));
        when(statsRepository.findSince(TODAY))
                .thenReturn(
                        List.of(
                                stats(1, 10, 0, 12, 3),
                                stats(2, 5, 5, 0, 0),
                                stats(4, 2, 0, 1, 0)));
        when(stateRepository.findBySourceId(5L))
                .thenReturn(Optional.of(state(5, 2, NOW.plus(Duration.ofMinutes(10)), null)));

        FeedDashboardView view = service.dashboard();

        long rowSum =
                view.sources().stream()
                        .mapToLong(FeedDashboardView.SourceRowView::todayNewCount)
                        .sum();
        assertThat(view.global().todayNewCount()).isEqualTo(13L).isEqualTo(rowSum);
        assertThat(view.global().todayDupCount()).isEqualTo(3L);
        // 活跃 = 今日 ≥1 成功轮：t114_a(10-0) + t114_d(2-0)；t114_b 全败不计
        assertThat(view.global().activeSourceCount()).isEqualTo(2);
        // 失败源 = 今日有失败（t114_b）或退避态（t114_e）
        assertThat(view.global().failedSourceCount()).isEqualTo(2);
    }

    // —— 感知延迟口径（REQ 场景 6 / 拍板三-1 / ADR-0045） ——

    @Test
    void latency_excludesFirstDayBackfillAndDailyGranularitySources() {
        InfoSource minuteSrc = source(1, "t114_a", true);
        InfoSource freshSrc = source(2, "t114_b", true);
        InfoSource dailySrc = source(3, "ndrc_policy", true);
        when(infoSourceRepository.findAll()).thenReturn(List.of(minuteSrc, freshSrc, dailySrc));
        when(itemRepository.findFirstIngestAt())
                .thenReturn(
                        Map.of(
                                1L, NOW.minus(Duration.ofDays(2)), // 老源：今日为增量日
                                2L, NOW.minus(Duration.ofHours(1)), // 新源：今日 = 首日（回灌日）
                                3L, NOW.minus(Duration.ofDays(5))));
        when(itemRepository.fetchLatencySamplesSince(TODAY_START.toString()))
                .thenReturn(
                        List.of(
                                sample(1, Duration.ofMinutes(3)),
                                sample(1, Duration.ofMinutes(5)),
                                sample(2, Duration.ofMinutes(300)), // 首日回灌（含 50 条量级的旧条目）
                                sample(3, Duration.ofMinutes(1)))); // 日粒度源（published_at 失真）

        FeedDashboardView.LatencyView latency = service.dashboard().global().latency();

        // 回灌 300min 与日粒度样本被排除：P50/P90 仅由增量轮条目主导（≈分钟量级）
        assertThat(latency.sampleCount()).isEqualTo(2);
        assertThat(latency.p50Millis()).isEqualTo(Duration.ofMinutes(3).toMillis());
        assertThat(latency.p90Millis()).isEqualTo(Duration.ofMinutes(5).toMillis());
        assertThat(latency.basis()).isEqualTo(FeedDashboardView.LATENCY_BASIS);
        assertThat(latency.excludedSourceCodes())
                .containsExactlyInAnyOrderElementsOf(FeedDashboardService.DAILY_GRANULARITY_CODES);
    }

    @Test
    void latency_noSamples_returnsNullsWithBasisAnnotated() {
        when(infoSourceRepository.findAll()).thenReturn(List.of(source(1, "t114_a", true)));

        FeedDashboardView.LatencyView latency = service.dashboard().global().latency();

        assertThat(latency.p50Millis()).isNull();
        assertThat(latency.p90Millis()).isNull();
        assertThat(latency.sampleCount()).isZero();
        assertThat(latency.basis()).isEqualTo(FeedDashboardView.LATENCY_BASIS);
        assertThat(latency.excludedSourceCodes()).isNotEmpty();
    }

    // —— 排序与五态（REQ 场景 3） ——

    @Test
    void rowOrder_abnormalFirstThenTodayNewDesc() {
        InfoSource okBig = source(1, "t114_a", true);
        InfoSource failSmall = source(2, "t114_b", true);
        InfoSource backoffZero = source(3, "t114_c", true);
        when(infoSourceRepository.findAll()).thenReturn(List.of(okBig, failSmall, backoffZero));
        when(statsRepository.findSince(TODAY))
                .thenReturn(List.of(stats(1, 8, 0, 10, 0), stats(2, 4, 1, 3, 0)));
        when(stateRepository.findBySourceId(1L)).thenReturn(Optional.of(state(1, 0, null, null)));
        when(stateRepository.findBySourceId(2L)).thenReturn(Optional.of(state(2, 1, null, "连接超时")));
        when(stateRepository.findBySourceId(3L))
                .thenReturn(Optional.of(state(3, 3, NOW.plus(Duration.ofMinutes(30)), "DNS 解析失败")));

        List<FeedDashboardView.SourceRowView> rows = service.dashboard().sources();

        assertThat(rows)
                .extracting(FeedDashboardView.SourceRowView::sourceCode)
                .containsExactly("t114_b", "t114_c", "t114_a");
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            if (row.sourceCode().equals("t114_a")) {
                                assertThat(row.runState()).isEqualTo("ok");
                                assertThat(row.abnormal()).isFalse();
                            } else {
                                assertThat(row.abnormal()).isTrue();
                            }
                        });
    }

    @Test
    void runState_fiveStateMatrix_matchesSourcePageBadgeSemantics() {
        InfoSource disabled = source(1, "t114_a", false);
        InfoSource neverPolled = source(2, "t114_b", true);
        InfoSource ok = source(3, "t114_c", true);
        InfoSource fail = source(4, "t114_d", true);
        InfoSource backoff = source(5, "t114_e", true);
        when(infoSourceRepository.findAll())
                .thenReturn(List.of(disabled, neverPolled, ok, fail, backoff));
        when(stateRepository.findBySourceId(3L)).thenReturn(Optional.of(state(3, 0, null, null)));
        when(stateRepository.findBySourceId(4L)).thenReturn(Optional.of(state(4, 1, null, "连接超时")));
        when(stateRepository.findBySourceId(5L))
                .thenReturn(Optional.of(state(5, 2, NOW.plus(Duration.ofMinutes(5)), "DNS")));

        List<FeedDashboardView.SourceRowView> rows = service.dashboard().sources();

        // 排序副作用：异常态（fail/backoff）置顶，其余按名称稳定序
        assertThat(rows)
                .extracting(FeedDashboardView.SourceRowView::sourceCode)
                .containsExactly("t114_d", "t114_e", "t114_a", "t114_b", "t114_c");
        assertThat(rows)
                .extracting(FeedDashboardView.SourceRowView::runState)
                .containsExactly("fail", "backoff", "disabled", "pending", "ok");
    }

    // —— 失败列表（REQ 场景 4） ——

    @Test
    void failures_mergedFromEventsAndLiveState_timeDescCappedAt20() {
        InfoSource known = source(1, "t114_a", true);
        when(infoSourceRepository.findAll()).thenReturn(List.of(known));
        List<FeedFailureEvent> events = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            // 21 条旁路事件（时间倒序入桩）：i=0 最新
            events.add(
                    new FeedFailureEvent(
                            i % 2 == 0 ? "t114_a" : "t114_gone",
                            NOW.minus(Duration.ofMinutes(i + 1)),
                            "consecutiveFailures=" + (i + 1) + "; FeedFetchException: 超时"));
        }
        when(failureEventRepository.findRecent(FeedDashboardService.FAILURE_LIST_LIMIT))
                .thenReturn(events);
        when(stateRepository.findBySourceId(1L))
                .thenReturn(Optional.of(state(1, 1, null, "运行态错误"))); // 现态失败（最新）

        List<FeedDashboardView.FailureView> failures = service.dashboard().failures();

        assertThat(failures).hasSize(FeedDashboardService.FAILURE_LIST_LIMIT);
        // 最新在前：现态条目（NOW）置顶，其后旁路事件按时间倒序
        assertThat(failures.get(0).origin()).isEqualTo("state");
        assertThat(failures.get(0).sourceName()).isEqualTo("t114_a 名称");
        assertThat(failures.get(1).origin()).isEqualTo("event");
        // 未知源代码（已删源）回落以代码为名
        assertThat(failures)
                .anySatisfy(
                        f -> {
                            assertThat(f.sourceCode()).isEqualTo("t114_gone");
                            assertThat(f.sourceName()).isEqualTo("t114_gone");
                        });
        // 时间倒序
        assertThat(failures)
                .isSortedAccordingTo((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
    }

    @Test
    void failures_liveStateOnlyIncludedWhenLastErrorAndAttemptAfterSuccess() {
        InfoSource recovered = source(1, "t114_a", true);
        when(infoSourceRepository.findAll()).thenReturn(List.of(recovered));
        SourcePollState recoveredState =
                new SourcePollState(
                        1L,
                        NOW.minus(Duration.ofMinutes(1)),
                        NOW, // 成功晚于尝试：已恢复，lastError 为历史残留 → 不入列
                        NOW.plus(Duration.ofMinutes(5)),
                        null,
                        null,
                        0,
                        null,
                        100L,
                        "new=1",
                        "历史错误",
                        NOW.minus(Duration.ofDays(1)),
                        NOW);
        when(stateRepository.findBySourceId(1L)).thenReturn(Optional.of(recoveredState));

        assertThat(service.dashboard().failures()).isEmpty();
    }

    // —— 空数据（REQ 场景 7） ——

    @Test
    void emptyDashboard_zerosAndNullLatencyWithoutError() {
        FeedDashboardView view = service.dashboard();

        assertThat(view.sources()).isEmpty();
        assertThat(view.failures()).isEmpty();
        assertThat(view.global().todayNewCount()).isZero();
        assertThat(view.global().activeSourceCount()).isZero();
        assertThat(view.global().failedSourceCount()).isZero();
        assertThat(view.global().latency().p50Millis()).isNull();
    }

    // —— 夹具 ——

    private static InfoSource source(long id, String code, boolean enabled) {
        return InfoSource.reconstruct(
                id,
                code,
                code.equals("t114_a") ? "t114_a 名称" : code,
                "快讯",
                AdapterType.RSS,
                null,
                "https://example.com/" + code,
                SourceConfig.empty(),
                5,
                enabled,
                false,
                false,
                NOW.minus(Duration.ofDays(3)),
                NOW.minus(Duration.ofDays(3)));
    }

    private static SourceDailyStats stats(
            long sourceId, long poll, long fail, long newCount, long dup) {
        return new SourceDailyStats(
                sourceId * 100, sourceId, TODAY, poll, fail, newCount, dup, NOW, NOW);
    }

    /** 运行态夹具：lastAttempt=NOW、lastSuccess=NOW-10min（最近一轮为败）或按需特化。 */
    private static SourcePollState state(
            long sourceId, int failures, Instant backoffUntil, String lastError) {
        return new SourcePollState(
                sourceId,
                NOW,
                NOW.minus(Duration.ofMinutes(10)),
                NOW.plus(Duration.ofMinutes(5)),
                null,
                null,
                failures,
                backoffUntil,
                200L,
                "new=0",
                lastError,
                NOW.minus(Duration.ofDays(3)),
                NOW);
    }

    private static FeedItemRepository.LatencySample sample(long sourceId, Duration latency) {
        return new FeedItemRepository.LatencySample(sourceId, NOW, Math.max(0, latency.toMillis()));
    }

    // ---- T128：源维度行透出 staleSince 疑似停更徽章数据面（方案 §4.7 / ADR-0049 裁量 6） ----

    @Test
    void dashboard_sourceRowCarriesStaleSinceFromConfig() {
        InfoSource stale =
                InfoSource.reconstruct(
                        9L,
                        "t128_stale",
                        "停更源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t128",
                        SourceConfig.empty().withStaleSince("2026-09-15"),
                        5,
                        true,
                        false,
                        false,
                        NOW.minus(Duration.ofDays(30)),
                        NOW.minus(Duration.ofDays(30)));
        InfoSource healthy = source(10, "t128_ok", true);
        when(infoSourceRepository.findAll()).thenReturn(List.of(stale, healthy));

        FeedDashboardView view = service.dashboard();

        FeedDashboardView.SourceRowView staleRow =
                view.sources().stream()
                        .filter(row -> row.sourceCode().equals("t128_stale"))
                        .findFirst()
                        .orElseThrow();
        FeedDashboardView.SourceRowView healthyRow =
                view.sources().stream()
                        .filter(row -> row.sourceCode().equals("t128_ok"))
                        .findFirst()
                        .orElseThrow();
        assertThat(staleRow.staleSince()).isEqualTo("2026-09-15");
        assertThat(healthyRow.staleSince()).isNull();
    }
}
