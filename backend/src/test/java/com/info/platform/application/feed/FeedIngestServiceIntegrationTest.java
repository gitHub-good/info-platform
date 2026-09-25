package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FeedEventRecorder;
import com.info.platform.domain.feed.FeedFetcher;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FeedIngestService 集成测试（T103，方案 §4.4 事务边界）：真实 SQLite 仓储 + fake FeedFetcher—— 条目批量落库 + 游标同事务推进 +
 * 统计自增一事务；崩溃重拉幂等（不重不漏）；失败退避序列与事件节流；gap&gt;3×interval 触发深翻。
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedIngestServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String STAT_DATE =
            LocalDate.ofInstant(NOW, java.time.ZoneId.of("Asia/Shanghai")).toString();

    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private com.info.platform.domain.feed.FeedItemRepository itemRepository;
    @Autowired private SourcePollStateRepository stateRepository;
    @Autowired private SourceDailyStatsRepository statsRepository;
    @Autowired private FeedEventRecorder eventRecorder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final AtomicReference<Supplier<FetchResult>> fetchBehavior = new AtomicReference<>();
    private final AtomicReference<FetchContext> lastContext = new AtomicReference<>();

    private FeedIngestService service;
    private InfoSource source;
    private Long sourceId;

    @BeforeEach
    void setUp() {
        fetchBehavior.set(() -> FetchResult.of(List.of()));
        FeedFetcher fake =
                (src, ctx) -> {
                    lastContext.set(ctx);
                    return fetchBehavior.get().get();
                };
        service =
                new FeedIngestService(
                        fake,
                        itemRepository,
                        stateRepository,
                        statsRepository,
                        eventRecorder,
                        transactionTemplate,
                        CLOCK);
        source =
                InfoSource.create(
                        "t103_ingest",
                        "摄取源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss",
                        new SourceConfig(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                CursorType.ID,
                                "externalId"),
                        5,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't103_%')");
        jdbcTemplate.update(
                "DELETE FROM source_daily_stats WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't103_%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't103_%')");
        jdbcTemplate.update("DELETE FROM data_source_event WHERE source_code LIKE 'info:t103_%'");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't103_%'");
    }

    private static RawFeedItem item(String externalId, String title, Instant publishedAt) {
        return new RawFeedItem(
                externalId,
                title,
                "摘要",
                "https://example.com/n/" + externalId,
                "作者",
                publishedAt,
                externalId);
    }

    private Optional<SourcePollState> state() {
        return stateRepository.findBySourceId(sourceId);
    }

    @Test
    void poll_success_persistsItemsAdvancesCursorAndStatsInOneRound() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        jdbcTemplate.update(
                "UPDATE source_poll_state SET cursor_value = '100', last_success_at = ? "
                        + "WHERE source_id = ?",
                NOW.minusSeconds(120).toString(),
                sourceId);
        fetchBehavior.set(
                () ->
                        FetchResult.of(
                                List.of(
                                        item("103", "标题103", NOW.minusSeconds(30)),
                                        item("102", "标题102", NOW.minusSeconds(60)),
                                        // 同源重复 external_id → OR IGNORE 计 dup
                                        item("102", "标题102重复", NOW.minusSeconds(60)))));

        boolean ok = service.poll(source);

        assertThat(ok).isTrue();
        // 条目：3 应插 2 实插（102 首个入库者胜）
        assertThat(itemRepository.findLatest(sourceId, null, 10)).hasSize(2);
        SourcePollState after = state().orElseThrow();
        // 游标同事务推进至本轮最大（ID 数值）
        assertThat(after.cursorValue()).isEqualTo("103");
        assertThat(after.cursorUpdatedAt()).isNotNull();
        assertThat(after.nextDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(after.lastAttemptAt()).isEqualTo(NOW);
        assertThat(after.lastSuccessAt()).isEqualTo(NOW);
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(after.backoffUntil()).isNull();
        assertThat(after.lastRoundDetail()).isEqualTo("new=2; dup=1; pages=1; backfill=none");
        // 统计自增：poll=1 fail=0 new=2 dup=1
        var stats =
                statsRepository.findSince(STAT_DATE).stream()
                        .filter(s -> s.sourceId() == sourceId)
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.pollCount()).isEqualTo(1);
        assertThat(stats.failCount()).isZero();
        assertThat(stats.newCount()).isEqualTo(2);
        assertThat(stats.dupCount()).isEqualTo(1);
    }

    @Test
    void poll_rerunSameBatch_zeroNewDuplicateAbsorbed_cursorStable() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        fetchBehavior.set(
                () -> FetchResult.of(List.of(item("201", "重拉甲", NOW), item("202", "重拉乙", NOW))));

        service.poll(source);
        boolean okSecond = service.poll(source);

        // 崩溃/重拉场景：同批重放 → 全部 dup，游标不退不跳
        assertThat(okSecond).isTrue();
        SourcePollState after = state().orElseThrow();
        assertThat(after.cursorValue()).isEqualTo("202");
        var stats =
                statsRepository.findSince(STAT_DATE).stream()
                        .filter(s -> s.sourceId() == sourceId)
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.pollCount()).isEqualTo(2);
        assertThat(stats.newCount()).isEqualTo(2);
        assertThat(stats.dupCount()).isEqualTo(2);
    }

    @Test
    void poll_filtersBlankTitleAndOrphanItems_capsAtMaxItems() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        java.util.List<RawFeedItem> mixed =
                new java.util.ArrayList<>(
                        List.of(
                                item("300", "正常条目", NOW),
                                // 空标题：过滤（§4.2）
                                item("301", "  ", NOW),
                                // 无 url 无 external_id：过滤
                                new RawFeedItem(null, "孤儿条目", "s", null, null, NOW, null)));
        for (int i = 0; i < 5; i++) {
            mixed.add(item("310" + i, "上限条目" + i, NOW));
        }
        // maxItems=3：单轮入库上限
        source.edit(
                null,
                null,
                null,
                new SourceConfig(
                        null, null, null, null, null, 3, null, CursorType.ID, "externalId"),
                null,
                null);
        infoSourceRepository.save(source);
        fetchBehavior.set(() -> FetchResult.of(mixed));

        service.poll(source);

        assertThat(itemRepository.findLatest(sourceId, null, 10)).hasSize(3);
        var stats =
                statsRepository.findSince(STAT_DATE).stream()
                        .filter(s -> s.sourceId() == sourceId)
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.newCount()).isEqualTo(3);
        assertThat(stats.dupCount()).isZero();
    }

    @Test
    void poll_failure_backoffExponentialAndEventThrottled() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        RuntimeException boom = new IllegalStateException("feed exploded");
        fetchBehavior.set(
                () -> {
                    throw new RuntimeException(boom);
                });

        boolean ok = service.poll(source);

        assertThat(ok).isFalse();
        SourcePollState after = state().orElseThrow();
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        // interval 5min × 2^1 = 10min
        assertThat(after.backoffUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(after.lastError()).contains("feed exploded");
        var stats =
                statsRepository.findSince(STAT_DATE).stream()
                        .filter(s -> s.sourceId() == sourceId)
                        .findFirst()
                        .orElseThrow();
        assertThat(stats.pollCount()).isEqualTo(1);
        assertThat(stats.failCount()).isEqualTo(1);

        // 首败旁路事件（info: 前缀 + event_type=3 错误）
        Integer events = countEvents();
        assertThat(events).isEqualTo(1);

        // 连续失败 2~9：不再记事件（防刷屏），退避按 2^n 递增
        for (int round = 2; round <= 9; round++) {
            assertThat(service.poll(source)).isFalse();
        }
        assertThat(countEvents()).isEqualTo(1);
        SourcePollState ninth = state().orElseThrow();
        // 5min × 2^min(9,4)=16 → 80min 封顶 60min
        assertThat(ninth.backoffUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        // 第 10 次：补记一条（每 10 次节流）
        assertThat(service.poll(source)).isFalse();
        assertThat(countEvents()).isEqualTo(2);
    }

    @Test
    void poll_failureThenSuccess_backoffCleared() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        fetchBehavior.set(
                () -> {
                    throw new IllegalStateException("transient");
                });
        service.poll(source);
        fetchBehavior.set(() -> FetchResult.of(List.of(item("400", "恢复", NOW))));

        boolean ok = service.poll(source);

        assertThat(ok).isTrue();
        SourcePollState after = state().orElseThrow();
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(after.backoffUntil()).isNull();
    }

    @Test
    void poll_gapBeyondThreeIntervals_triggersBackfillPagesAndTruncatedDetail() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        // last_success 4 个间隔前 → gap > 3×interval → 深翻 3 页
        jdbcTemplate.update(
                "UPDATE source_poll_state SET last_success_at = ? WHERE source_id = ?",
                NOW.minus(Duration.ofMinutes(20)).toString(),
                sourceId);
        fetchBehavior.set(() -> new FetchResult(List.of(item("500", "补抓", NOW)), true));

        service.poll(source);

        assertThat(lastContext.get().pages()).isEqualTo(FeedIngestService.MAX_BACKFILL_PAGES);
        assertThat(lastContext.get().cursorValue()).isNull();
        SourcePollState after = state().orElseThrow();
        assertThat(after.lastRoundDetail()).isEqualTo("new=1; dup=0; pages=3; backfill=truncated");
    }

    @Test
    void poll_smallGap_singlePage() {
        stateRepository.insertIfAbsent(sourceId, NOW, NOW.minusSeconds(3600));
        jdbcTemplate.update(
                "UPDATE source_poll_state SET last_success_at = ? WHERE source_id = ?",
                NOW.minus(Duration.ofMinutes(9)).toString(),
                sourceId);
        fetchBehavior.set(() -> FetchResult.of(List.of(item("501", "正常", NOW))));

        service.poll(source);

        assertThat(lastContext.get().pages()).isEqualTo(1);
        assertThat(state().orElseThrow().lastRoundDetail()).endsWith("pages=1; backfill=none");
    }

    @Test
    void poll_noStateRow_createsAndAdvances() {
        // 新源首抓（注册服务尚未建状态行）：摄取自建并推进
        fetchBehavior.set(() -> FetchResult.of(List.of(item("600", "首抓", NOW))));

        boolean ok = service.poll(source);

        assertThat(ok).isTrue();
        SourcePollState after = state().orElseThrow();
        assertThat(after.cursorValue()).isEqualTo("600");
        assertThat(after.nextDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void backoffAndPages_pureFunctions_sequenceAndCap() {
        // 退避序列：interval×2^n，n≤4，封顶 60min
        Instant t = Instant.parse("2026-09-22T00:00:00Z");
        assertThat(FeedIngestService.backoffUntil(t, 5, 1))
                .isEqualTo(t.plus(Duration.ofMinutes(10)));
        assertThat(FeedIngestService.backoffUntil(t, 5, 2))
                .isEqualTo(t.plus(Duration.ofMinutes(20)));
        assertThat(FeedIngestService.backoffUntil(t, 5, 4))
                .isEqualTo(t.plus(Duration.ofMinutes(60))); // 5×2^4=80 封顶 60
        assertThat(FeedIngestService.backoffUntil(t, 5, 9))
                .isEqualTo(t.plus(Duration.ofMinutes(60)));
        assertThat(FeedIngestService.backoffUntil(t, 30, 1))
                .isEqualTo(t.plus(Duration.ofMinutes(60))); // 30×2=60 已到顶

        // 页数：无成功基线 → 1；gap ≤ 3×interval → 1；gap > 3×interval → 3
        assertThat(FeedIngestService.pagesFor(t, null, 5)).isEqualTo(1);
        assertThat(FeedIngestService.pagesFor(t, t.minus(Duration.ofMinutes(15)), 5)).isEqualTo(1);
        assertThat(FeedIngestService.pagesFor(t, t.minus(Duration.ofMinutes(16)), 5))
                .isEqualTo(FeedIngestService.MAX_BACKFILL_PAGES);
    }

    private Integer countEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_source_event WHERE source_code = ? AND event_type = 3",
                Integer.class,
                "info:t103_ingest");
    }
}
