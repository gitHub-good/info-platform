package com.info.platform.application.feed;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFetcher;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.RobotsPolicyChecker;
import com.info.platform.domain.feed.RobotsPolicyChecker.RobotsVerdict;
import com.info.platform.domain.feed.SourceConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 资讯源注册管理编排（M13 T105，方案 §4.5）：CRUD / 启停 / 软删恢复 / 连通性干跑 / 手动抓取受理 / 数据面组装。
 *
 * <h2>关键语义</h2>
 *
 * <ul>
 *   <li>新增：sourceCode 后端从名称生成（ASCII 清洗 / 纯中文名哈希短码——编排者裁定），冲突 30072； robots 禁抓 30075 硬拦截；next_due_at
 *       = now → 首抓 ≤1 个调度周期（≤10 分钟出数红线）。
 *   <li>编辑/启停：行内配置即真相，下一 tick 现读热生效（无重启生效项）；不触碰运行态行。
 *   <li>软删：仅通用源（预置 30073）；deleted=1 + 停用 + 条目保留；恢复回停用态。
 *   <li>连通性测试：robots 复判 + 单页干跑（不落库），200 恒返回诊断体。
 *   <li>数据面：逐源逐日 rollup + 全局感知延迟 P50/P90（news_item 现算，§4.8）。
 * </ul>
 */
@Service
public class SourceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistryService.class);

    /** 分组展示顺序（未知分组按首现顺序追加；方案 §4.1 category 取值域）。 */
    private static final List<String> CATEGORY_ORDER = List.of("快讯", "媒体", "政策", "宏观", "国际", "自建");

    /** 连通性测试解析样本上限（方案 §4.5 示例 ≤3 条）。 */
    private static final int CONNECTIVITY_SAMPLE_LIMIT = 3;

    private static final ZoneId ZONE_SH = ZoneId.of("Asia/Shanghai");

    private final InfoSourceRepository infoSourceRepository;
    private final SourcePollStateRepository stateRepository;
    private final SourceDailyStatsRepository statsRepository;
    private final FeedItemRepository itemRepository;
    private final SourceConfigValidator validator;
    private final RobotsPolicyChecker robotsChecker;
    private final FeedFetcher fetcher;
    private final SourceSchedulingService schedulingService;
    private final Clock clock;

    public SourceRegistryService(
            InfoSourceRepository infoSourceRepository,
            SourcePollStateRepository stateRepository,
            SourceDailyStatsRepository statsRepository,
            FeedItemRepository itemRepository,
            SourceConfigValidator validator,
            RobotsPolicyChecker robotsChecker,
            FeedFetcher fetcher,
            SourceSchedulingService schedulingService,
            Clock clock) {
        this.infoSourceRepository = infoSourceRepository;
        this.stateRepository = stateRepository;
        this.statsRepository = statsRepository;
        this.itemRepository = itemRepository;
        this.validator = validator;
        this.robotsChecker = robotsChecker;
        this.fetcher = fetcher;
        this.schedulingService = schedulingService;
        this.clock = clock;
    }

    /** 分组列表：active 按 category 分组（CATEGORY_ORDER 优先）+ archived 归档组（软删源单独列出）。 */
    public InfoSourcesListView list() {
        String today = today();
        Map<Long, InfoSourceCardView.TodayCountersView> counters = todayCounters(today);
        List<InfoSource> all = infoSourceRepository.findAll();
        Map<String, List<InfoSourceCardView>> grouped = new LinkedHashMap<>();
        List<InfoSourceCardView> archived = new ArrayList<>();
        for (InfoSource source : all) {
            InfoSourceCardView card = toCard(source, counters);
            if (source.isDeleted()) {
                archived.add(card);
                continue;
            }
            grouped.computeIfAbsent(source.getCategory(), c -> new ArrayList<>()).add(card);
        }
        List<InfoSourcesListView.CategoryGroup> groups =
                grouped.entrySet().stream()
                        .sorted(
                                Comparator.comparingInt(
                                        (Map.Entry<String, List<InfoSourceCardView>> e) ->
                                                categoryRank(e.getKey())))
                        .map(e -> new InfoSourcesListView.CategoryGroup(e.getKey(), e.getValue()))
                        .toList();
        return new InfoSourcesListView(groups, archived);
    }

    /**
     * 新增通用源（保存即启用：next_due_at = now，下一个 tick 首抓）。
     *
     * @throws BusinessException 30072 配置校验失败 / sourceCode 冲突；30075 robots 禁抓
     */
    public InfoSourceCardView create(CreateCommand command) {
        validator.validateCreateType(command.adapterType());
        // BUG-01（M13 验收）：endpoint 非空/格式校验先于 robots 检查——null 端点进 URI.create 抛 NPE 致 500，
        // 应为 400/30072 字段级提示
        if (command.endpoint() == null || command.endpoint().isBlank()) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID, "endpoint: 必填（源数据地址）");
        }
        RobotsVerdict robots = robotsChecker.check(command.endpoint());
        if (!robots.allowed()) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_ROBOTS_FORBIDDEN,
                    "robots.txt 禁止抓取该端点（" + robots.note() + "）");
        }
        String sourceCode = sourceCodeFromName(command.name());
        if (infoSourceRepository.findBySourceCode(sourceCode).isPresent()) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID,
                    "sourceCode 生成冲突（" + sourceCode + "，同名源已存在），请修改名称");
        }
        InfoSource source =
                construct(
                        () ->
                                InfoSource.create(
                                        sourceCode,
                                        command.name(),
                                        command.category(),
                                        command.adapterType(),
                                        null,
                                        command.endpoint(),
                                        command.config(),
                                        command.intervalMinutes(),
                                        command.enabled() == null || command.enabled(),
                                        false));
        validator.validateCreate(source);
        infoSourceRepository.save(source);
        // 新建源 next_due_at = now：不等错峰，下一个 tick 即首抓（蓝图故事 1 场景 1）
        stateRepository.insertIfAbsent(source.getId(), clock.instant(), clock.instant());
        log.info(
                "新增资讯源 source={} adapter={} interval={}min",
                source.getSourceCode(),
                source.getAdapterType(),
                source.getIntervalMinutes());
        return toCard(source, todayCounters(today()));
    }

    /**
     * 编辑（部分字段合并，下一 tick 现读热生效）。
     *
     * @throws BusinessException 30071 不存在；30072 校验失败 / adapterType 变更；30075 robots 禁抓（端点变更时）
     */
    public InfoSourceCardView update(long id, UpdateCommand command) {
        InfoSource source = requireSource(id);
        if (command.adapterType() != null
                && !command.adapterType().isBlank()
                && AdapterType.from(command.adapterType()) != source.getAdapterType()) {
            throw new BusinessException(
                    ErrorCode.INFO_SOURCE_CONFIG_INVALID,
                    "adapterType 不可变更（当前 " + source.getAdapterType().wireCode() + "）");
        }
        if (command.endpoint() != null
                && !command.endpoint().equals(source.getEndpoint())
                && (command.endpoint().startsWith("http://")
                        || command.endpoint().startsWith("https://"))) {
            RobotsVerdict robots = robotsChecker.check(command.endpoint());
            if (!robots.allowed()) {
                throw new BusinessException(
                        ErrorCode.INFO_SOURCE_ROBOTS_FORBIDDEN,
                        "robots.txt 禁止抓取该端点（" + robots.note() + "）");
            }
        }
        mutate(
                () ->
                        source.edit(
                                command.name(),
                                command.category(),
                                command.endpoint(),
                                command.config(),
                                command.intervalMinutes(),
                                command.enabled()));
        validator.validateCommon(source);
        infoSourceRepository.save(source);
        log.info("编辑资讯源 source={}（下一 tick 热生效）", source.getSourceCode());
        return toCard(source, todayCounters(today()));
    }

    /** 启停（下一 tick 摘除/加入；不触碰运行态——停用源的 next_due 保留，恢复后按原节奏）。 */
    public InfoSourceCardView changeEnabled(long id, boolean enabled) {
        InfoSource source = requireSource(id);
        source.changeEnabled(enabled);
        infoSourceRepository.save(source);
        log.info("资讯源{} source={}", enabled ? "启用" : "停用", source.getSourceCode());
        return toCard(source, todayCounters(today()));
    }

    /**
     * 软删（仅通用源）：deleted=1 + 停用 + 调度摘除 + 条目保留（news_item 不动，默认流 join 排除）。
     *
     * @throws BusinessException 30071 不存在；30073 预置源
     */
    public InfoSourceCardView archive(long id) {
        InfoSource source = requireSource(id);
        if (source.isPreset()) {
            throw new BusinessException(ErrorCode.INFO_SOURCE_PRESET_DELETE_FORBIDDEN);
        }
        source.markDeleted();
        infoSourceRepository.save(source);
        log.info("资讯源软删归档 source={}（历史条目保留）", source.getSourceCode());
        return toCard(source, todayCounters(today()));
    }

    /** 恢复软删源：deleted=0，恢复为停用态（启用时机由用户确认，UI §3.4）。 */
    public InfoSourceCardView restore(long id) {
        InfoSource source = requireSource(id);
        source.restore();
        infoSourceRepository.save(source);
        log.info("资讯源恢复（停用态） source={}", source.getSourceCode());
        return toCard(source, todayCounters(today()));
    }

    /**
     * 连通性干跑：robots 复判 + 单页取数（不落库、不推进游标），200 恒返回诊断体（失败原因在 body）。
     *
     * @throws BusinessException 30071 不存在
     */
    public ConnectivityTestResultView connectivityTest(long id) {
        InfoSource source = requireSource(id);
        RobotsVerdict robots = robotsChecker.check(source.getEndpoint());
        if (!robots.allowed()) {
            return new ConnectivityTestResultView(
                    false, false, null, 0, "robots.txt 禁止抓取该端点（" + robots.note() + "）", List.of());
        }
        long startNanos = System.nanoTime();
        try {
            List<RawFeedItem> items = fetcher.fetch(source, FetchContext.firstPage(null)).items();
            long latency = (System.nanoTime() - startNanos) / 1_000_000;
            return new ConnectivityTestResultView(
                    true,
                    true,
                    latency,
                    items.size(),
                    null,
                    items.stream()
                            .limit(CONNECTIVITY_SAMPLE_LIMIT)
                            .map(
                                    item ->
                                            new ConnectivityTestResultView.SampleItem(
                                                    item.title(),
                                                    item.url(),
                                                    item.publishedAt() == null
                                                            ? null
                                                            : item.publishedAt().toString()))
                            .toList());
        } catch (Exception e) {
            long latency = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("连通性测试失败 source={}: {}", source.getSourceCode(), e.getMessage());
            return new ConnectivityTestResultView(
                    false, true, latency, 0, truncate(e.getMessage(), 300), List.of());
        }
    }

    /**
     * 手动抓取受理（异步 202 通道）：加载源后委派调度服务 CAS 守卫派发。
     *
     * @throws BusinessException 30071 不存在；30074 该源抓取在飞
     */
    public InfoSource submitPoll(long id) {
        InfoSource source = requireSource(id);
        schedulingService.submitPollNow(source);
        return source;
    }

    /**
     * 数据面：逐源逐日 rollup（findSince 组装）+ 各源今日计数与运行态 + 全局感知延迟 P50/P90。
     *
     * @param days 窗口天数（控制器已归一化 1~30）
     */
    public SourceStatsView stats(int days) {
        String today = today();
        LocalDate from = LocalDate.ofInstant(clock.instant(), ZONE_SH).minusDays(days - 1L);
        Map<Long, InfoSourceCardView.TodayCountersView> counters = todayCounters(today);
        List<InfoSource> all = infoSourceRepository.findAll();
        List<SourceStatsView.SourceStatsSummary> sources =
                all.stream()
                        .map(
                                s ->
                                        new SourceStatsView.SourceStatsSummary(
                                                s.getId(),
                                                s.getSourceCode(),
                                                s.getName(),
                                                counters.getOrDefault(
                                                        s.getId(),
                                                        InfoSourceCardView.TodayCountersView.EMPTY),
                                                stateSummary(s.getId())))
                        .toList();
        Map<Long, String> codeById =
                all.stream()
                        .collect(Collectors.toMap(InfoSource::getId, InfoSource::getSourceCode));
        List<SourceStatsView.DailyRollup> daily =
                statsRepository.findSince(from.toString()).stream()
                        .map(
                                row ->
                                        new SourceStatsView.DailyRollup(
                                                row.sourceId(),
                                                codeById.getOrDefault(row.sourceId(), "?"),
                                                row.statDate(),
                                                row.pollCount(),
                                                row.failCount(),
                                                row.newCount(),
                                                row.dupCount()))
                        .toList();
        List<Long> latencies =
                itemRepository.fetchLatencyMillisSince(
                        from.atStartOfDay(ZONE_SH).toInstant().toString());
        return new SourceStatsView(
                days, from.toString(), sources, daily, percentilePair(latencies));
    }

    /** 新增命令（sourceCode 后端从名称生成——编排者裁定）。 */
    public record CreateCommand(
            String name,
            String category,
            AdapterType adapterType,
            String endpoint,
            Integer intervalMinutes,
            Boolean enabled,
            SourceConfig config) {}

    /** 编辑命令（部分字段合并；adapterType 不可变，携带异值即 30072）。 */
    public record UpdateCommand(
            String name,
            String category,
            String adapterType,
            String endpoint,
            Integer intervalMinutes,
            Boolean enabled,
            SourceConfig config) {}

    // —— 组装辅助 ——

    private InfoSource requireSource(long id) {
        return infoSourceRepository
                .findById(id)
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.INFO_SOURCE_NOT_FOUND, "id=" + id));
    }

    /** 实体构造/变更的非法参数统一收敛为 30072 字段级提示（频控 1~60/必填等规则在实体把守）。 */
    private InfoSource construct(java.util.function.Supplier<InfoSource> constructor) {
        try {
            return constructor.get();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INFO_SOURCE_CONFIG_INVALID, e.getMessage());
        }
    }

    private void mutate(Runnable mutation) {
        try {
            mutation.run();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INFO_SOURCE_CONFIG_INVALID, e.getMessage());
        }
    }

    private InfoSourceCardView toCard(
            InfoSource source, Map<Long, InfoSourceCardView.TodayCountersView> counters) {
        return InfoSourceCardView.of(
                source,
                counters.getOrDefault(source.getId(), InfoSourceCardView.TodayCountersView.EMPTY),
                stateSummary(source.getId()));
    }

    private InfoSourceCardView.PollStateSummaryView stateSummary(long sourceId) {
        return stateRepository
                .findBySourceId(sourceId)
                .map(SourceRegistryService::toStateSummary)
                .orElse(InfoSourceCardView.PollStateSummaryView.EMPTY);
    }

    private static InfoSourceCardView.PollStateSummaryView toStateSummary(SourcePollState state) {
        return new InfoSourceCardView.PollStateSummaryView(
                iso(state.lastAttemptAt()),
                iso(state.lastSuccessAt()),
                iso(state.nextDueAt()),
                state.cursorValue(),
                state.consecutiveFailures(),
                iso(state.backoffUntil()),
                state.lastDurationMillis(),
                state.lastRoundDetail(),
                state.lastError());
    }

    private Map<Long, InfoSourceCardView.TodayCountersView> todayCounters(String today) {
        return statsRepository.findSince(today).stream()
                .filter(row -> row.statDate().equals(today))
                .collect(
                        Collectors.toMap(
                                SourceDailyStats::sourceId,
                                row ->
                                        new InfoSourceCardView.TodayCountersView(
                                                row.pollCount(),
                                                row.failCount(),
                                                row.newCount(),
                                                row.dupCount())));
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZONE_SH).toString();
    }

    private static int categoryRank(String category) {
        int rank = CATEGORY_ORDER.indexOf(category);
        return rank >= 0 ? rank : CATEGORY_ORDER.size();
    }

    /** 感知延迟 P50/P90（最近邻秩法；无样本 null——与 0 可区分）。 */
    private static SourceStatsView.Latency percentilePair(List<Long> samples) {
        if (samples.isEmpty()) {
            return new SourceStatsView.Latency(null, null, 0);
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        return new SourceStatsView.Latency(
                sorted.get(percentileIndex(sorted.size(), 50)),
                sorted.get(percentileIndex(sorted.size(), 90)),
                sorted.size());
    }

    /** 最近邻秩：ceil(p% × n) − 1（clamp 到末位）。 */
    private static int percentileIndex(int size, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * size) - 1;
        return Math.min(Math.max(index, 0), size - 1);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "未知原因";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * sourceCode 生成（编排者裁定：用户不填）：名称 ASCII 清洗小写（≥2 位可用）；纯中文/清洗后不足 → 名称哈希短码（n + base36）。 确定性生成保证同名冲突可被
     * 30072 识别（用户改名即换码）。
     */
    static String sourceCodeFromName(String name) {
        String sanitized =
                name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (sanitized.length() >= 2) {
            return sanitized.length() > 32 ? sanitized.substring(0, 32) : sanitized;
        }
        return "n" + Integer.toUnsignedString((name == null ? "" : name).hashCode(), 36);
    }
}
