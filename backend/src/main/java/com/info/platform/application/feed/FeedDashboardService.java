package com.info.platform.application.feed;

import com.info.platform.domain.feed.FeedFailureEventRepository;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 抓取大盘聚合服务（M14 T114，REQ 故事 2）：三区块一端点只读组装——全局统计 / 源维度表 / 近期失败列表。
 *
 * <h2>对账口径（验收锁）</h2>
 *
 * 今日计数全部来自 {@code source_daily_stats} 当日行（与源管理页 /info-sources 同表同数）； {@code global.todayNewCount}
 * 由源维度各行今日新增求和而得，构造上即「全局 = 分项之和」。
 *
 * <h2>感知延迟口径（ADR-0045，REQ 拍板三-1「仅增量轮」）</h2>
 *
 * 仅统计当日样本，且排除：① 每源首日入库条目（首轮回灌简化判定——REQ 风险表授权口径，版本化于 {@link FeedDashboardView#LATENCY_BASIS}）；②
 * 日粒度源（ndrc/csrc/stats/em_macro——published_at 仅日期粒度，感知延迟失真）。
 *
 * <h2>排序口径（REQ 场景 3）</h2>
 *
 * 异常态（失败/退避中）置顶，其余按今日新增降序，再按名称稳定排序。
 */
@Service
public class FeedDashboardService {

    private static final ZoneId ZONE_SH = ZoneId.of("Asia/Shanghai");

    /** 近期失败列表条数（REQ 场景 4「最近 ≥20 条」）。 */
    static final int FAILURE_LIST_LIMIT = 20;

    /**
     * 日粒度源（published_at 仅日期/序列粒度，感知延迟口径失真——排除统计并在响应中标注，ADR-0045）。
     *
     * <p>口径：官方政策三源（列表页日期墙钟）+ 东财宏观（指标序列无发布时刻）。新增日粒度源时在此登记。
     */
    static final List<String> DAILY_GRANULARITY_CODES =
            List.of("ndrc_policy", "csrc_news", "stats_release", "em_macro_indicators");

    private final InfoSourceRepository infoSourceRepository;
    private final SourcePollStateRepository stateRepository;
    private final SourceDailyStatsRepository statsRepository;
    private final FeedItemRepository itemRepository;
    private final FeedFailureEventRepository failureEventRepository;
    private final Clock clock;

    public FeedDashboardService(
            InfoSourceRepository infoSourceRepository,
            SourcePollStateRepository stateRepository,
            SourceDailyStatsRepository statsRepository,
            FeedItemRepository itemRepository,
            FeedFailureEventRepository failureEventRepository,
            Clock clock) {
        this.infoSourceRepository = infoSourceRepository;
        this.stateRepository = stateRepository;
        this.statsRepository = statsRepository;
        this.itemRepository = itemRepository;
        this.failureEventRepository = failureEventRepository;
        this.clock = clock;
    }

    /** 三区块组装（只读，无写副作用）。 */
    public FeedDashboardView dashboard() {
        Instant now = clock.instant();
        String today = LocalDate.ofInstant(now, ZONE_SH).toString();
        List<InfoSource> sources = infoSourceRepository.findAll();
        Map<Long, SourceDailyStats> todayRows = todayRows(today);
        Map<Long, SourcePollState> states = statesOf(sources);
        Map<Long, Long> totals = itemRepository.countGroupedBySource();
        List<FeedDashboardView.SourceRowView> rows =
                sources.stream()
                        .map(s -> toRow(s, todayRows, states, totals, now))
                        .sorted(rowOrder())
                        .toList();
        FailuresResult failures = failures(sources, states, now);
        return new FeedDashboardView(
                toGlobal(rows, sources, today), rows, failures.visible(), failures.hidden());
    }

    // —— global 区块 ——

    private FeedDashboardView.GlobalView toGlobal(
            List<FeedDashboardView.SourceRowView> rows, List<InfoSource> sources, String today) {
        long todayNew =
                rows.stream().mapToLong(FeedDashboardView.SourceRowView::todayNewCount).sum();
        long todayDup =
                rows.stream().mapToLong(FeedDashboardView.SourceRowView::todayDupCount).sum();
        Instant now = clock.instant();
        return new FeedDashboardView.GlobalView(
                todayNew,
                todayDup,
                (int) rows.stream().filter(FeedDashboardService::isActiveToday).count(),
                (int) rows.stream().filter(row -> isFailedToday(row, now)).count(),
                latency(sources, today));
    }

    /** 活跃源：今日 ≥1 次成功抓取（成功轮数 = poll − fail，REQ 场景 1）。 */
    private static boolean isActiveToday(FeedDashboardView.SourceRowView row) {
        return row.todayPollCount() - row.todayFailCount() >= 1;
    }

    /** 失败源：今日有失败或处于退避态（REQ 场景 1；退避判定与五态徽章矩阵一致）。 */
    private static boolean isFailedToday(FeedDashboardView.SourceRowView row, Instant now) {
        boolean backoff =
                row.consecutiveFailures() >= 2
                        && row.backoffUntil() != null
                        && Instant.parse(row.backoffUntil()).isAfter(now);
        return row.todayFailCount() > 0 || backoff;
    }

    /** 感知延迟：当日样本 → 排除每源首日 + 排除日粒度源 → P50/P90（最近邻秩公用实现）。 */
    private FeedDashboardView.LatencyView latency(List<InfoSource> sources, String today) {
        Instant todayStart = LocalDate.parse(today).atStartOfDay(ZONE_SH).toInstant();
        Map<Long, String> codeById =
                sources.stream()
                        .collect(Collectors.toMap(InfoSource::getId, InfoSource::getSourceCode));
        Map<Long, String> firstDayBySource = new HashMap<>();
        itemRepository
                .findFirstIngestAt()
                .forEach(
                        (sourceId, firstAt) ->
                                firstDayBySource.put(
                                        sourceId,
                                        LocalDate.ofInstant(firstAt, ZONE_SH).toString()));
        List<Long> samples = new ArrayList<>();
        for (FeedItemRepository.LatencySample sample :
                itemRepository.fetchLatencySamplesSince(todayStart.toString())) {
            String code = codeById.get(sample.sourceId());
            if (code == null || DAILY_GRANULARITY_CODES.contains(code)) {
                continue; // 日粒度源：published_at 口径失真，排除（响应中标注）
            }
            if (today.equals(firstDayBySource.get(sample.sourceId()))) {
                continue; // 每源首日：首轮回灌排除（ADR-0045 简化口径）
            }
            samples.add(sample.latencyMillis());
        }
        LatencyPercentiles.Pair pair = LatencyPercentiles.percentilePair(samples);
        return new FeedDashboardView.LatencyView(
                pair.p50(),
                pair.p90(),
                samples.size(),
                FeedDashboardView.LATENCY_BASIS,
                DAILY_GRANULARITY_CODES);
    }

    // —— sources 区块 ——

    private FeedDashboardView.SourceRowView toRow(
            InfoSource source,
            Map<Long, SourceDailyStats> todayRows,
            Map<Long, SourcePollState> states,
            Map<Long, Long> totals,
            Instant now) {
        SourceDailyStats stats = todayRows.get(source.getId());
        SourcePollState state = states.get(source.getId());
        SourceRunState runState = SourceRunState.of(source, state, now);
        return new FeedDashboardView.SourceRowView(
                source.getId(),
                source.getSourceCode(),
                source.getName(),
                source.getCategory(),
                source.getAdapterType().wireCode(),
                source.getIntervalMinutes(),
                source.isEnabled(),
                source.isPreset(),
                source.isDeleted(),
                source.getConfig().staleSince(), // 疑似停更徽章数据面（M15 T128）
                stats == null ? 0 : stats.pollCount(),
                stats == null ? 0 : stats.newCount(),
                stats == null ? 0 : stats.failCount(),
                stats == null ? 0 : stats.dupCount(),
                totals.getOrDefault(source.getId(), 0L),
                iso(state == null ? null : state.lastAttemptAt()),
                iso(state == null ? null : state.lastSuccessAt()),
                iso(state == null ? null : state.nextDueAt()),
                iso(state == null ? null : state.backoffUntil()),
                state == null ? 0 : state.consecutiveFailures(),
                state == null ? null : state.lastError(),
                state == null ? null : state.lastRoundDetail(),
                runState.wireCode(),
                runState.abnormal());
    }

    /** 排序：异常态置顶 → 其余今日新增降序 → 名称稳定序（REQ 场景 3）。 */
    private static Comparator<FeedDashboardView.SourceRowView> rowOrder() {
        return Comparator.comparing(
                        FeedDashboardView.SourceRowView::abnormal, Comparator.reverseOrder())
                .thenComparing(
                        FeedDashboardView.SourceRowView::todayNewCount, Comparator.reverseOrder())
                .thenComparing(FeedDashboardView.SourceRowView::name);
    }

    // —— failures 区块 ——

    /**
     * 失败列表组装结果（T211）：visible = 恢复过滤后展示条目；hidden = 被过滤隐藏条数。
     *
     * @param visible 时间倒序截 {@value #FAILURE_LIST_LIMIT} 的展示列表
     * @param hidden 被恢复过滤隐藏的合并条目数（留痕在库零删除，仅大盘展示过滤）
     */
    private record FailuresResult(List<FeedDashboardView.FailureView> visible, long hidden) {}

    /**
     * 失败列表：旁路事件（主源，节流内幕如实）+ 运行态 last_error 现态补充，时间倒序截 {@value #FAILURE_LIST_LIMIT}；T211
     * 恢复过滤（REQ-20260928-20 拍板四）——仅当前 runState ∈ {fail, backoff} 的源展示失败记录，已恢复（ok）/停用/归档/已物理删除源（源行不在
     * findAll，runState 未知）零展示； 事件表留痕零删除，追溯走 Job 日志/失败事件留痕面。
     */
    private FailuresResult failures(
            List<InfoSource> sources, Map<Long, SourcePollState> states, Instant now) {
        Map<String, InfoSource> byCode =
                sources.stream().collect(Collectors.toMap(InfoSource::getSourceCode, s -> s));
        Map<Long, String> codeById =
                sources.stream()
                        .collect(Collectors.toMap(InfoSource::getId, InfoSource::getSourceCode));
        Set<String> failingCodes =
                sources.stream()
                        .filter(s -> SourceRunState.of(s, states.get(s.getId()), now).abnormal())
                        .map(InfoSource::getSourceCode)
                        .collect(Collectors.toSet());
        List<FeedDashboardView.FailureView> merged = new ArrayList<>();
        failureEventRepository.findRecent(FAILURE_LIST_LIMIT).stream()
                .map(
                        event ->
                                new FeedDashboardView.FailureView(
                                        event.sourceCode(),
                                        nameOf(byCode, event.sourceCode()),
                                        event.occurredAt().toString(),
                                        event.detail(),
                                        "event"))
                .forEach(merged::add);
        states.forEach(
                (sourceId, state) -> {
                    if (state.lastError() == null || !isLiveFailure(state)) {
                        return;
                    }
                    String code = codeById.get(sourceId);
                    if (code == null) {
                        return;
                    }
                    merged.add(
                            new FeedDashboardView.FailureView(
                                    code,
                                    nameOf(byCode, code),
                                    state.lastAttemptAt().toString(),
                                    state.lastError(),
                                    "state"));
                });
        merged.sort(
                Comparator.comparing(
                        FeedDashboardView.FailureView::occurredAt, Comparator.reverseOrder()));
        List<FeedDashboardView.FailureView> visible =
                merged.stream().filter(f -> failingCodes.contains(f.sourceCode())).toList();
        long hidden = merged.size() - visible.size();
        return new FailuresResult(
                List.copyOf(visible.stream().limit(FAILURE_LIST_LIMIT).toList()), hidden);
    }

    /** 现态失败：最近尝试晚于最近成功（最近一轮是失败且 last_error 在案）。 */
    private static boolean isLiveFailure(SourcePollState state) {
        return state.lastAttemptAt() != null
                && (state.lastSuccessAt() == null
                        || state.lastAttemptAt().isAfter(state.lastSuccessAt()));
    }

    private static String nameOf(Map<String, InfoSource> byCode, String sourceCode) {
        InfoSource source = byCode.get(sourceCode);
        return source == null ? sourceCode : source.getName();
    }

    // —— 组装辅助 ——

    private Map<Long, SourceDailyStats> todayRows(String today) {
        return statsRepository.findSince(today).stream()
                .filter(row -> row.statDate().equals(today))
                .collect(Collectors.toMap(SourceDailyStats::sourceId, row -> row));
    }

    private Map<Long, SourcePollState> statesOf(List<InfoSource> sources) {
        Map<Long, SourcePollState> states = new HashMap<>();
        for (InfoSource source : sources) {
            stateRepository
                    .findBySourceId(source.getId())
                    .ifPresent(state -> states.put(source.getId(), state));
        }
        return states;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
