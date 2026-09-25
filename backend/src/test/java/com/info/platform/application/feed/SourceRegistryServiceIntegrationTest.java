package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FeedFetcher;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.RobotsPolicyChecker;
import com.info.platform.domain.feed.RobotsPolicyChecker.RobotsVerdict;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import com.info.platform.domain.feed.SourcePollState;
import com.info.platform.domain.feed.SourcePollStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * SourceRegistryService 集成测试（T105，方案 §4.5 全矩阵）：真实 SQLite 仓储 + fake 取数/robots（零外呼）； 覆盖新增（sourceCode
 * 生成/30072 字段级/30075 robots 硬拦截）、编辑热生效、启停、软删恢复边界（预置 30073 / 条目保留）、 分组视图 + 归档组、连通性干跑不落库、 stats
 * 数据面组装（rollup/今日计数/P50-P90 感知延迟）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceRegistryServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String STAT_DATE = "2026-09-22";

    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private SourcePollStateRepository stateRepository;
    @Autowired private SourceDailyStatsRepository statsRepository;
    @Autowired private FeedItemRepository itemRepository;
    @Autowired private SourceConfigValidator validator;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final AtomicReference<Supplier<FetchResult>> fetchBehavior = new AtomicReference<>();
    private final AtomicReference<RobotsVerdict> robotsVerdict =
            new AtomicReference<>(new RobotsVerdict(true, "无 robots（404）"));
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private final SourceSchedulingService schedulingService = mock(SourceSchedulingService.class);
    private final List<String> extraCleanupCodes = new java.util.ArrayList<>();

    private SourceRegistryService service;

    @BeforeEach
    void setUp() {
        fetchBehavior.set(() -> FetchResult.of(List.of()));
        FeedFetcher fakeFetcher =
                (source, ctx) -> {
                    fetchCalls.incrementAndGet();
                    return fetchBehavior.get().get();
                };
        RobotsPolicyChecker fakeRobots = endpoint -> robotsVerdict.get();
        service =
                new SourceRegistryService(
                        infoSourceRepository,
                        stateRepository,
                        statsRepository,
                        itemRepository,
                        validator,
                        fakeRobots,
                        fakeFetcher,
                        schedulingService,
                        CLOCK);
    }

    @AfterEach
    void cleanup() {
        for (String code : extraCleanupCodes) {
            jdbcTemplate.update(
                    "DELETE FROM news_item WHERE source_id IN (SELECT id FROM info_source WHERE source_code = ?)",
                    code);
            jdbcTemplate.update(
                    "DELETE FROM source_daily_stats WHERE source_id IN (SELECT id FROM info_source WHERE source_code = ?)",
                    code);
            jdbcTemplate.update(
                    "DELETE FROM source_poll_state WHERE source_id IN (SELECT id FROM info_source WHERE source_code = ?)",
                    code);
            jdbcTemplate.update("DELETE FROM info_source WHERE source_code = ?", code);
        }
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't105%')");
        jdbcTemplate.update(
                "DELETE FROM source_daily_stats WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't105%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't105%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't105%'");
    }

    // —— 新增（sourceCode 生成 + robots 硬拦截 + 字段级 30072） ——

    @Test
    void create_generatesCodeFromName_savesEnabledWithNextDueNow() {
        InfoSourceCardView card =
                service.create(
                        new SourceRegistryService.CreateCommand(
                                "T105 My Feed 01",
                                "自建",
                                AdapterType.RSS,
                                "https://example.com/rss.xml",
                                15,
                                null,
                                SourceConfig.empty()));

        assertThat(card.sourceCode()).isEqualTo("t105myfeed01");
        assertThat(card.enabled()).isTrue();
        assertThat(card.preset()).isFalse();
        assertThat(card.adapterType()).isEqualTo("rss");
        InfoSource saved = infoSourceRepository.findById(card.id()).orElseThrow();
        assertThat(saved.isEnabled()).isTrue();
        // 新建源 next_due_at = now：下一个 tick 即首抓（≤10 分钟出数红线）
        assertThat(stateRepository.findBySourceId(card.id()).orElseThrow().nextDueAt())
                .isEqualTo(NOW);
    }

    @Test
    void create_pureChineseName_fallsBackToHashShortCode() {
        InfoSourceCardView card =
                service.create(
                        new SourceRegistryService.CreateCommand(
                                "我的快讯源",
                                "自建",
                                AdapterType.RSS,
                                "https://example.com/rss.xml",
                                15,
                                null,
                                SourceConfig.empty()));

        assertThat(card.sourceCode()).matches("n[0-9a-z]{1,11}");
        assertThat(infoSourceRepository.findBySourceCode(card.sourceCode())).isPresent();
        // 哈希短码无 t105 前缀：登记后由 cleanup 显式删除
        extraCleanupCodes.add(card.sourceCode());
    }

    @Test
    void create_sourceCodeConflict_30072() {
        service.create(command("T105 Same Name", "https://example.com/a.xml"));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        command("T105 Same Name", "https://example.com/a.xml")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_CONFIG_INVALID);
    }

    @Test
    void create_robotsForbidden_30075_nothingPersisted() {
        robotsVerdict.set(new RobotsVerdict(false, "Disallow: /flash"));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SourceRegistryService.CreateCommand(
                                                "T105 Blocked Feed",
                                                "自建",
                                                AdapterType.RSS,
                                                "https://example.com/blocked.xml",
                                                15,
                                                null,
                                                SourceConfig.empty())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_ROBOTS_FORBIDDEN);
        assertThat(infoSourceRepository.findBySourceCode("t105blockedfeed")).isEmpty();
    }

    @Test
    void create_intervalOutOfRange_30072_fieldLevelMessage() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SourceRegistryService.CreateCommand(
                                                "T105 Bad Interval",
                                                "自建",
                                                AdapterType.RSS,
                                                "https://example.com/rss.xml",
                                                0,
                                                null,
                                                SourceConfig.empty())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("intervalMinutes");
    }

    @Test
    void create_jsonApiWithoutTitleMapping_30072() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SourceRegistryService.CreateCommand(
                                                "T105 No Mapping Json",
                                                "自建",
                                                AdapterType.JSON_API,
                                                "https://example.com/api.js",
                                                5,
                                                null,
                                                SourceConfig.empty())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemMapping");
    }

    @Test
    void create_presetChannelRejected_30072() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new SourceRegistryService.CreateCommand(
                                                "T105 Preset Try",
                                                "自建",
                                                AdapterType.PRESET,
                                                "https://example.com/p",
                                                5,
                                                null,
                                                SourceConfig.empty())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("adapterType");
    }

    // —— 编辑 / 启停（热生效：下一 tick 现读） ——

    @Test
    void update_mergesParams_hotEffectiveNextTick_adapterTypeImmutable() {
        InfoSourceCardView created =
                service.create(command("T105 Edit Me", "https://example.com/e.xml"));
        stateRepository.insertIfAbsent(created.id(), NOW, NOW);

        InfoSourceCardView updated =
                service.update(
                        created.id(),
                        new SourceRegistryService.UpdateCommand(
                                "编辑后",
                                "媒体",
                                null,
                                "https://example.com/e2.xml",
                                30,
                                false,
                                new SourceConfig(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        10,
                                        null,
                                        CursorType.ID,
                                        "externalId")));

        assertThat(updated.name()).isEqualTo("编辑后");
        assertThat(updated.category()).isEqualTo("媒体");
        assertThat(updated.endpoint()).isEqualTo("https://example.com/e2.xml");
        assertThat(updated.intervalMinutes()).isEqualTo(30);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.config().maxItems()).isEqualTo(10);
        InfoSource saved = infoSourceRepository.findById(created.id()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("编辑后");
        // 编辑不触碰运行态（next_due 不重置，热生效由下一 tick 现读保证）
        assertThat(stateRepository.findBySourceId(created.id()).orElseThrow().nextDueAt())
                .isEqualTo(NOW);

        assertThatThrownBy(
                        () ->
                                service.update(
                                        created.id(),
                                        new SourceRegistryService.UpdateCommand(
                                                null, null, "json_api", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_CONFIG_INVALID);
    }

    @Test
    void update_missingSource_30071() {
        assertThatThrownBy(
                        () ->
                                service.update(
                                        999999L,
                                        new SourceRegistryService.UpdateCommand(
                                                "x", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_NOT_FOUND);
    }

    @Test
    void changeEnabled_disableThenEnable_roundTrip() {
        InfoSourceCardView created =
                service.create(command("T105 Toggle", "https://example.com/t.xml"));

        InfoSourceCardView disabled = service.changeEnabled(created.id(), false);
        assertThat(disabled.enabled()).isFalse();
        InfoSourceCardView enabled = service.changeEnabled(created.id(), true);
        assertThat(enabled.enabled()).isTrue();
        assertThatThrownBy(() -> service.changeEnabled(999999L, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_NOT_FOUND);
    }

    // —— 软删 / 恢复（预置 30073；条目保留；归档组可见） ——

    @Test
    void archive_genericSource_softDeletesAndKeepsItems_listedInArchivedGroup() {
        InfoSourceCardView created =
                service.create(command("T105 Archive Me", "https://example.com/ar.xml"));
        persistItem(created.id(), "ar-1", "归档源条目", NOW);

        InfoSourceCardView archived = service.archive(created.id());

        assertThat(archived.deleted()).isTrue();
        assertThat(archived.enabled()).isFalse();
        // 历史条目保留但默认流排除（join deleted=0）
        assertThat(itemRepository.findLatest(created.id(), null, 10)).isEmpty();
        assertThat(itemRepository.countByFilter(created.id())).isZero();
        // 预置种子源在分组中恒在（本断言只看本源）：软删后从全部 active 分组消失、出现在归档组
        InfoSourcesListView view = service.list();
        List<String> activeCodes =
                view.groups().stream()
                        .flatMap(g -> g.sources().stream())
                        .map(InfoSourceCardView::sourceCode)
                        .toList();
        assertThat(activeCodes).doesNotContain(created.sourceCode());
        assertThat(view.archived())
                .extracting(InfoSourceCardView::sourceCode)
                .containsExactly(created.sourceCode());
    }

    @Test
    void archive_presetSource_30073() {
        InfoSource preset =
                InfoSource.create(
                        "t105_preset_src",
                        "预置试点源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/preset.xml",
                        SourceConfig.empty(),
                        15,
                        true,
                        true);
        infoSourceRepository.save(preset);

        assertThatThrownBy(() -> service.archive(preset.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_PRESET_DELETE_FORBIDDEN);
    }

    @Test
    void restore_returnsToDisabledState_notScheduled() {
        InfoSourceCardView created =
                service.create(command("T105 Restore Me", "https://example.com/re.xml"));
        service.archive(created.id());

        InfoSourceCardView restored = service.restore(created.id());

        assertThat(restored.deleted()).isFalse();
        // 恢复回停用态：启用时机由用户确认（UI §3.4）
        assertThat(restored.enabled()).isFalse();
        assertThat(infoSourceRepository.findActive())
                .extracting(InfoSource::getId)
                .doesNotContain(created.id());
    }

    // —— 分组视图 —— —

    @Test
    void list_groupsByKnownCategoryOrder_withArchivedSeparate() {
        InfoSourceCardView flash =
                service.create(command("T105 快讯源A", "快讯", "https://example.com/f.xml"));
        InfoSourceCardView custom1 =
                service.create(command("T105 自建源B", "https://example.com/c1.xml"));
        InfoSourceCardView custom2 =
                service.create(command("T105 自建源C", "https://example.com/c2.xml"));
        InfoSourceCardView archived =
                service.create(command("T105 归档源D", "https://example.com/c3.xml"));
        service.archive(archived.id());

        InfoSourcesListView view = service.list();

        // 分组按 CATEGORY_ORDER 排序（快讯 < 国际 < 自建；预置种子快讯/国际组恒在）
        List<String> categories =
                view.groups().stream().map(InfoSourcesListView.CategoryGroup::category).toList();
        assertThat(categories).containsSubsequence("快讯", "国际", "自建");
        java.util.function.Function<String, List<String>> codesOf =
                category ->
                        view.groups().stream()
                                .filter(g -> g.category().equals(category))
                                .findFirst()
                                .orElseThrow()
                                .sources()
                                .stream()
                                .map(InfoSourceCardView::sourceCode)
                                .toList();
        assertThat(codesOf.apply("快讯")).contains(flash.sourceCode());
        assertThat(codesOf.apply("自建")).containsExactly(custom1.sourceCode(), custom2.sourceCode());
        assertThat(view.archived())
                .extracting(InfoSourceCardView::sourceCode)
                .containsExactly(archived.sourceCode());
    }

    @Test
    void list_cardsCarryTodayCountersAndPollStateSummary() {
        InfoSourceCardView created =
                service.create(command("T105 计数源", "https://example.com/cnt.xml"));
        statsRepository.increment(created.id(), STAT_DATE, 3, 1, 2, 1);
        stateRepository.insertIfAbsent(created.id(), NOW, NOW);
        SourcePollState failed =
                new SourcePollState(
                        created.id(),
                        NOW,
                        null,
                        NOW.plus(Duration.ofMinutes(5)),
                        null,
                        null,
                        2,
                        NOW.plus(Duration.ofMinutes(10)),
                        120L,
                        "new=0; dup=0; pages=1; backfill=none",
                        "连接超时",
                        NOW,
                        NOW);
        stateRepository.update(failed);

        InfoSourceCardView card =
                service.list().groups().stream()
                        .flatMap(g -> g.sources().stream())
                        .filter(c -> c.id().equals(created.id()))
                        .findFirst()
                        .orElseThrow();

        assertThat(card.today().pollCount()).isEqualTo(3);
        assertThat(card.today().failCount()).isEqualTo(1);
        assertThat(card.today().newCount()).isEqualTo(2);
        assertThat(card.today().dupCount()).isEqualTo(1);
        assertThat(card.state().consecutiveFailures()).isEqualTo(2);
        assertThat(card.state().lastError()).isEqualTo("连接超时");
        assertThat(card.state().backoffUntil()).isNotNull();
    }

    // —— 连通性测试（干跑不落库；robots 复判拒抓） ——

    @Test
    void connectivityTest_success_returnsDiagnosticsWithoutPersisting() {
        InfoSourceCardView created =
                service.create(command("T105 Conn Ok", "https://example.com/ok.xml"));
        fetchBehavior.set(
                () ->
                        FetchResult.of(
                                List.of(
                                        rawItem("1", "样本一", NOW.minusSeconds(60)),
                                        rawItem("2", "样本二", NOW.minusSeconds(50)),
                                        rawItem("3", "样本三", NOW.minusSeconds(40)),
                                        rawItem("4", "样本四", NOW.minusSeconds(30)))));

        ConnectivityTestResultView result = service.connectivityTest(created.id());

        assertThat(result.reachable()).isTrue();
        assertThat(result.robotsAllowed()).isTrue();
        assertThat(result.parsedCount()).isEqualTo(4);
        assertThat(result.sampleItems()).hasSize(3);
        assertThat(result.sampleItems().get(0).title()).isEqualTo("样本一");
        assertThat(result.latencyMillis()).isNotNull();
        // 干跑不落库
        assertThat(itemRepository.countByFilter(created.id())).isZero();
    }

    @Test
    void connectivityTest_fetchFails_returnsDiagnosticBody() {
        InfoSourceCardView created =
                service.create(command("T105 Conn Fail", "https://example.com/x.xml"));
        fetchBehavior.set(
                () -> {
                    throw new IllegalStateException("连接超时");
                });

        ConnectivityTestResultView result = service.connectivityTest(created.id());

        assertThat(result.reachable()).isFalse();
        assertThat(result.robotsAllowed()).isTrue();
        assertThat(result.error()).contains("连接超时");
        assertThat(result.parsedCount()).isZero();
        assertThat(result.sampleItems()).isEmpty();
    }

    @Test
    void connectivityTest_robotsDisallowed_refusesFetch() {
        InfoSourceCardView created =
                service.create(command("T105 Conn Robots", "https://example.com/r.xml"));
        robotsVerdict.set(new RobotsVerdict(false, "Disallow: /r.xml"));
        fetchCalls.set(0);

        ConnectivityTestResultView result = service.connectivityTest(created.id());

        assertThat(result.robotsAllowed()).isFalse();
        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("robots");
        assertThat(fetchCalls.get()).isZero();
    }

    @Test
    void connectivityTest_missingSource_30071() {
        assertThatThrownBy(() -> service.connectivityTest(999999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_NOT_FOUND);
    }

    // —— 手动抓取受理（异步 202 通道委派） ——

    @Test
    void submitPoll_delegatesToSchedulingWithLoadedSource() {
        InfoSourceCardView created =
                service.create(command("T105 Poll Me", "https://example.com/poll.xml"));

        InfoSource accepted = service.submitPoll(created.id());

        assertThat(accepted.getId()).isEqualTo(created.id());
        verify(schedulingService).submitPollNow(any(InfoSource.class));
    }

    @Test
    void submitPoll_missingSource_30071_noDispatch() {
        assertThatThrownBy(() -> service.submitPoll(999999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_NOT_FOUND);
        verify(schedulingService, never()).submitPollNow(any());
    }

    // —— stats 数据面（rollup + 今日计数 + 感知延迟 P50/P90） ——

    @Test
    void stats_assemblesDailyRollupTodayCountersAndLatencyPercentiles() {
        InfoSourceCardView created =
                service.create(command("T105 统计源", "https://example.com/stat.xml"));
        statsRepository.increment(created.id(), STAT_DATE, 2, 1, 5, 2);
        statsRepository.increment(created.id(), "2026-09-21", 4, 0, 7, 3);
        persistItem(created.id(), "s-1", "延迟六十秒", NOW.minusSeconds(60));
        persistItem(created.id(), "s-2", "延迟三十秒", NOW.minusSeconds(30));
        persistItem(created.id(), "s-3", "延迟十秒", NOW.minusSeconds(10));

        SourceStatsView stats = service.stats(7);

        assertThat(stats.days()).isEqualTo(7);
        assertThat(stats.daily())
                .filteredOn(r -> r.sourceId() == created.id())
                .extracting(SourceStatsView.DailyRollup::statDate)
                .containsExactly("2026-09-21", STAT_DATE);
        SourceStatsView.SourceStatsSummary summary =
                stats.sources().stream()
                        .filter(s -> s.sourceId() == created.id())
                        .findFirst()
                        .orElseThrow();
        assertThat(summary.sourceCode()).isEqualTo(created.sourceCode());
        assertThat(summary.today().newCount()).isEqualTo(5);
        // 最近邻秩：3 样本 [10s,30s,60s] → p50=30s、p90=60s
        assertThat(stats.latency().sampleCount()).isEqualTo(3);
        assertThat(stats.latency().p50Millis()).isEqualTo(30_000L);
        assertThat(stats.latency().p90Millis()).isEqualTo(60_000L);
    }

    @Test
    void stats_negativeLatencyClampedToZero_emptyWindowGivesNullPercentiles() {
        // 无样本窗口：p50/p90 为 null 而非 0（口径可区分）
        SourceStatsView empty = service.stats(30);
        assertThat(empty.latency().sampleCount()).isZero();
        assertThat(empty.latency().p50Millis()).isNull();
        assertThat(empty.latency().p90Millis()).isNull();

        InfoSourceCardView created =
                service.create(command("T105 时钟源", "https://example.com/clk.xml"));
        persistItem(created.id(), "clk-1", "源侧时钟超前", NOW.plusSeconds(5));
        persistItem(created.id(), "clk-2", "正常三十秒", NOW.minusSeconds(30));

        SourceStatsView stats = service.stats(30);
        // 样本 [0, 30000]（负值截 0）→ p50=0、p90=30000
        assertThat(stats.latency().p50Millis()).isZero();
        assertThat(stats.latency().p90Millis()).isEqualTo(30_000L);
    }

    // —— 辅件 ——

    private static SourceRegistryService.CreateCommand command(String name, String endpoint) {
        return command(name, "自建", endpoint);
    }

    private static SourceRegistryService.CreateCommand command(
            String name, String category, String endpoint) {
        return new SourceRegistryService.CreateCommand(
                name, category, AdapterType.RSS, endpoint, 15, null, SourceConfig.empty());
    }

    private static RawFeedItem rawItem(String externalId, String title, Instant publishedAt) {
        return new RawFeedItem(
                externalId,
                title,
                "摘要",
                "https://example.com/n/" + externalId,
                "作者",
                publishedAt,
                externalId);
    }

    private void persistItem(long sourceId, String externalId, String title, Instant publishedAt) {
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                sourceId,
                                externalId,
                                title,
                                null,
                                null,
                                null,
                                publishedAt,
                                NOW,
                                FeedFingerprint.fingerprint(title, publishedAt))));
    }
}
