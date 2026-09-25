package com.info.platform.application.feed;

import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FeedEventRecorder;
import com.info.platform.domain.feed.FeedFetcher;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 单源一轮摄取服务（M13 T103，方案 §4.4）：取数 → 过滤 → 指纹 → 批量 INSERT OR IGNORE → 游标同事务推进 → 统计自增。
 *
 * <h2>事务边界（不重不漏论证，ADR-0039/0040）</h2>
 *
 * 单事务 = 条目批量落库 + source_poll_state 推进 + source_daily_stats 自增。不重 = 双唯一索引 + OR
 * IGNORE（调度重入/补抓重拉/并发同稿收敛）； 不漏 = 游标只随条目成功推进（崩溃于事务后游标未推 → 下轮重拉同批 → 幂等吸收）。
 *
 * <h2>失败语义（退避摘除，ADR-0040）</h2>
 *
 * 任何取数/落库异常全捕获不上抛：consecutive_failures++ → backoff_until = now + min(interval×2^min(n,4), 60min)；
 * 成功清零。旁路事件走 {@link FeedEventRecorder}（首败与每 10 次节流）。深翻：gap &gt; 3×interval → 3
 * 页补抓，截断可观测（backfill=truncated）。
 */
@Service
public class FeedIngestService {

    private static final Logger log = LoggerFactory.getLogger(FeedIngestService.class);

    /** 深翻补抓页数上限（ADR-0040：maxBackfillPages=3）。 */
    public static final int MAX_BACKFILL_PAGES = 3;

    /** 断流判定系数：gap &gt; 3×interval 触发深翻。 */
    public static final int GAP_INTERVAL_MULTIPLIER = 3;

    /** 退避封顶（分钟，ADR-0040）。 */
    public static final long BACKOFF_CAP_MINUTES = 60;

    /** 退避指数上限：2^4 = 16×interval。 */
    private static final int BACKOFF_EXPONENT_CAP = 4;

    /** 事件节流：首败与每 10 次（ADR-0040）。 */
    private static final int EVENT_EVERY_N_FAILURES = 10;

    private static final ZoneId ZONE_SH = FeedFingerprint.ZONE_SH;

    private final FeedFetcher fetcher;
    private final FeedItemRepository itemRepository;
    private final SourcePollStateRepository stateRepository;
    private final SourceDailyStatsRepository statsRepository;
    private final FeedEventRecorder eventRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public FeedIngestService(
            FeedFetcher fetcher,
            FeedItemRepository itemRepository,
            SourcePollStateRepository stateRepository,
            SourceDailyStatsRepository statsRepository,
            FeedEventRecorder eventRecorder,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.fetcher = fetcher;
        this.itemRepository = itemRepository;
        this.stateRepository = stateRepository;
        this.statsRepository = statsRepository;
        this.eventRecorder = eventRecorder;
        this.txTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 单源单轮（异常全捕获不外抛——调用方为调度线程池/手动触发）。
     *
     * @return true = 本轮成功（含零条目）；false = 失败（已计退避与事件）
     */
    public boolean poll(InfoSource source) {
        Instant attemptStart = clock.instant();
        SourcePollState state = stateRepository.findBySourceId(source.getId()).orElse(null);
        try {
            int pages =
                    pagesFor(
                            attemptStart,
                            state == null ? null : state.lastSuccessAt(),
                            source.getIntervalMinutes());
            FetchResult result =
                    fetcher.fetch(
                            source,
                            new FetchContext(state == null ? null : state.cursorValue(), pages));
            ingest(source, state, result, attemptStart, pages);
            return true;
        } catch (Exception e) {
            recordFailure(source, state, attemptStart, e);
            return false;
        }
    }

    /** 成功轮：过滤/指纹在事务外（纯计算），落库/游标/统计在单事务内。 */
    private void ingest(
            InfoSource source,
            SourcePollState state,
            FetchResult result,
            Instant attemptStart,
            int pages) {
        Instant now = clock.instant();
        List<FeedItem> feeds = filterAndFingerprint(source, result.items(), now);
        String roundCursor =
                maxCursor(source, result.items(), state == null ? null : state.cursorValue());
        int inserted =
                txTemplate.execute(
                        txStatus -> {
                            int rows = itemRepository.insertIgnoreBatch(feeds);
                            int dup = feeds.size() - rows;
                            upsertState(
                                    source,
                                    successState(
                                            source,
                                            state,
                                            roundCursor,
                                            attemptStart,
                                            now,
                                            rows,
                                            dup,
                                            pages,
                                            result.truncated()));
                            statsRepository.increment(
                                    source.getId(), statDate(now), 1, 0, rows, dup);
                            return rows;
                        });
        log.info(
                "资讯源轮次完成 source={} new={} pages={} backfill={}",
                source.getSourceCode(),
                inserted,
                pages,
                result.truncated() ? "truncated" : pages > 1 ? "active" : "none");
    }

    /** §4.2 过滤：title 非空 && url/externalId 至少其一；maxItems 单轮上限；缺 publishedAt 回落抓取时刻。 */
    private List<FeedItem> filterAndFingerprint(
            InfoSource source, List<RawFeedItem> items, Instant now) {
        int cap = source.getConfig().effectiveMaxItems();
        List<FeedItem> feeds = new ArrayList<>(Math.min(cap, items.size()));
        for (RawFeedItem item : items) {
            if (feeds.size() >= cap) {
                break;
            }
            if (item.title() == null || item.title().isBlank()) {
                continue;
            }
            if (item.url() == null && item.externalId() == null) {
                continue;
            }
            Instant publishedAt = item.publishedAt() == null ? now : item.publishedAt();
            feeds.add(
                    FeedItem.newOf(
                            source.getId(),
                            item.externalId(),
                            item.title(),
                            item.summary(),
                            item.url(),
                            item.author(),
                            publishedAt,
                            now,
                            FeedFingerprint.fingerprint(item.title(), publishedAt)));
        }
        return feeds;
    }

    /** 成功后的运行态（游标只进不退：新值 = max(旧, 本轮最大)；next_due = 本轮开始 + interval）。 */
    private SourcePollState successState(
            InfoSource source,
            SourcePollState state,
            String roundCursor,
            Instant attemptStart,
            Instant now,
            int inserted,
            int dup,
            int pages,
            boolean truncated) {
        // 游标只进不退：仅当本轮最大值严格大于已存值（或首建）才推进
        boolean cursorAdvanced =
                roundCursor != null
                        && (state == null
                                || state.cursorValue() == null
                                || cursorGreater(
                                        roundCursor,
                                        state.cursorValue(),
                                        source.getConfig().effectiveCursorType()));
        return new SourcePollState(
                source.getId(),
                attemptStart,
                now,
                attemptStart.plus(Duration.ofMinutes(source.getIntervalMinutes())),
                cursorAdvanced ? roundCursor : state.cursorValue(),
                cursorAdvanced ? now : state == null ? null : state.cursorUpdatedAt(),
                0,
                null,
                Duration.between(attemptStart, now).toMillis(),
                roundDetail(inserted, dup, pages, truncated),
                null,
                state == null ? now : state.createdAt(),
                now);
    }

    private void recordFailure(
            InfoSource source, SourcePollState state, Instant attemptStart, Exception e) {
        Instant now = clock.instant();
        int failures = (state == null ? 0 : state.consecutiveFailures()) + 1;
        Instant backoff = backoffUntil(now, source.getIntervalMinutes(), failures);
        Instant nextDue = now.plus(Duration.ofMinutes(source.getIntervalMinutes()));
        String summary = truncate(e.toString(), 500);
        SourcePollState failed =
                new SourcePollState(
                        source.getId(),
                        attemptStart,
                        state == null ? null : state.lastSuccessAt(),
                        nextDue,
                        state == null ? null : state.cursorValue(),
                        state == null ? null : state.cursorUpdatedAt(),
                        failures,
                        backoff,
                        Duration.between(attemptStart, now).toMillis(),
                        state == null ? null : state.lastRoundDetail(),
                        summary,
                        state == null ? now : state.createdAt(),
                        now);
        txTemplate.executeWithoutResult(
                txStatus -> {
                    upsertState(source, failed);
                    statsRepository.increment(source.getId(), statDate(now), 1, 1, 0, 0);
                });
        if (failures == 1 || failures % EVENT_EVERY_N_FAILURES == 0) {
            eventRecorder.recordFailure(source.getSourceCode(), failures, summary);
        }
        log.warn(
                "资讯源轮次失败 source={} consecutiveFailures={} backoffUntil={}: {}",
                source.getSourceCode(),
                failures,
                backoff,
                summary);
    }

    /** 状态行 upsert：无行先插（新源首抓/首败）再更（update 全字段）。 */
    private void upsertState(InfoSource source, SourcePollState state) {
        if (!stateRepository.update(state)) {
            stateRepository.insertIfAbsent(source.getId(), state.nextDueAt(), state.updatedAt());
            stateRepository.update(state);
        }
    }

    /** 游标取本轮最大（与已存值比较只进不退；NONE 恒 null）。 */
    private static String maxCursor(InfoSource source, List<RawFeedItem> items, String current) {
        CursorType type = source.getConfig().effectiveCursorType();
        if (type == CursorType.NONE) {
            return null;
        }
        String max = current;
        for (RawFeedItem item : items) {
            String cursor = item.cursorValue();
            if (cursor == null) {
                continue;
            }
            if (max == null || cursorGreater(cursor, max, type)) {
                max = cursor;
            }
        }
        return max;
    }

    /** 游标比较（ID 数值 / TIME 时间；解析失败按不更大处理，保守不推进）。 */
    private static boolean cursorGreater(String candidate, String stored, CursorType type) {
        try {
            if (type == CursorType.ID) {
                return Long.parseLong(candidate.trim()) > Long.parseLong(stored.trim());
            }
            return Instant.parse(candidate).isAfter(Instant.parse(stored));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** ADR-0036 段式明细：new/dup/pages/backfill（none|active|truncated）。 */
    private static String roundDetail(int inserted, int dup, int pages, boolean truncated) {
        String backfill = truncated ? "truncated" : pages > 1 ? "active" : "none";
        return "new=" + inserted + "; dup=" + dup + "; pages=" + pages + "; backfill=" + backfill;
    }

    private static String statDate(Instant now) {
        return LocalDate.ofInstant(now, ZONE_SH).toString();
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 退避截止：{@code interval × 2^min(n,4)} 封顶 60min（纯函数可单测）。
     *
     * @param consecutiveFailures 自增后的连续失败数
     */
    public static Instant backoffUntil(Instant now, int intervalMinutes, int consecutiveFailures) {
        int exponent = Math.min(consecutiveFailures, BACKOFF_EXPONENT_CAP);
        long delayMinutes = Math.min((long) intervalMinutes << exponent, BACKOFF_CAP_MINUTES);
        return now.plusSeconds(delayMinutes * 60);
    }

    /**
     * 补抓页数：gap &gt; 3×interval → {@link #MAX_BACKFILL_PAGES} 深翻，否则 1 页（纯函数可单测）。
     *
     * @param lastSuccessAt 最近成功时刻（null = 无基线，首轮不深翻）
     */
    public static int pagesFor(Instant now, Instant lastSuccessAt, int intervalMinutes) {
        if (lastSuccessAt == null) {
            return 1;
        }
        Duration gap = Duration.between(lastSuccessAt, now);
        return gap.compareTo(Duration.ofMinutes((long) intervalMinutes * GAP_INTERVAL_MULTIPLIER))
                        > 0
                ? MAX_BACKFILL_PAGES
                : 1;
    }
}
