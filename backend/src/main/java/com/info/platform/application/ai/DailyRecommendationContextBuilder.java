package com.info.platform.application.ai;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每日推荐上下文装配器（应用层，T23，对齐 Spike-2 §7.4 每日推荐模板占位符 + §11.5；T29 增补个性化画像）。
 *
 * <p>补全 T21 遗留点：briefType=4（每日推荐型）的 {@code {{poolSize}}}/{@code {{subjectsMetrics}}}/{@code
 * {{subscribedThemes}}}/{@code {{today}}} 占位符不由 {@link BriefContextBuilder}（只投影个股聚合数据）装配，
 * 由本类作为「上游」在 {@link AIBriefService#generateAndPersist} 合并进上下文 Map（对齐 {@code BriefContextBuilder}
 * 注释「每日推荐占位符由 T23 装配」）。
 *
 * <p>装配链：按归属用户查全部启用 watchlist → 扁平化活跃清单项去重 subjectId → 对每只标的取行情/公告/新闻 SourceAdapter（复用 adapter 享缓存
 * + 弹性降级， 单源缺失不阻断整池）→ 组装 {@link PoolMetric}（信息面活跃度=|涨跌幅|，事件重要性=公告/新闻条数；T29 附 subjectId/industry
 * 供评分）→ 投影为上下文 Map + 指标列表。
 *
 * <p>T29 个性化注入（prompt v1.1 占位符）：经 {@link RecommendationPersonalizer} 装配用户画像后投影 —— {@code
 * subscribedThemes}（真实订阅主题词，修复 T23「暂无」桩）/{@code subscribedSubjects}（标的订阅代码|名称）/ {@code
 * readingProfile}（近 30 天已读标的：代码|名称|阅读次数|最近阅读日期）。空画像各占位符填「暂无」（不编造）。
 *
 * <p>容错：单只标的的 adapter 取数异常 → 记 WARN 跳过该指标（归 0），不阻断整池装配（对齐技术方案 §5 降级预案「每日推荐用规则兜底」）；画像装配失败由
 * Personalizer 内部降级为空画像。自选池空（无活跃清单项）→ 返回空指标 + {@code poolSize=0}，由 {@link
 * DailyRecommendationService} 返回空提示。
 *
 * <p>T46（ADR-0022）：实现 {@link PlaceholderProvider}，向占位符注册表自述本装配器实际注入的 6 键清单—— {@link #PLACEHOLDERS}
 * 与 {@link #toContext} 的 {@code ctx.put} 调用同文件同序维护，同源单测守护。
 */
@Component
public class DailyRecommendationContextBuilder implements PlaceholderProvider {

    private static final Logger log =
            LoggerFactory.getLogger(DailyRecommendationContextBuilder.class);

    private static final String NA = "暂无";

    /**
     * 本装配器实际注入的占位符描述符（T46 注册表单一事实源）。
     *
     * <p>键序与 {@link #toContext} 的 {@code ctx.put} 调用序逐一对齐——加/删键必须同时改两处，同源单测守护。
     */
    private static final List<PlaceholderDescriptor> PLACEHOLDERS =
            List.of(
                    new PlaceholderDescriptor("poolSize", "自选池标的数"),
                    new PlaceholderDescriptor("subjectsMetrics", "自选池指标快照（每只: 代码|名称|涨跌幅|公告数|新闻数）"),
                    new PlaceholderDescriptor("subscribedThemes", "订阅主题"),
                    new PlaceholderDescriptor("subscribedSubjects", "订阅标的（代码|名称）"),
                    new PlaceholderDescriptor("readingProfile", "近 30 天阅读画像（代码|名称|阅读次数|最近阅读日期）"),
                    new PlaceholderDescriptor("today", "今日日期"));

    private final WatchlistRepository watchlistRepository;
    private final SubjectRepository subjectRepository;
    private final RecommendationPersonalizer personalizer;
    private final Map<SourceCode, SourceAdapter> adapters;
    private final Clock clock;

    public DailyRecommendationContextBuilder(
            WatchlistRepository watchlistRepository,
            SubjectRepository subjectRepository,
            RecommendationPersonalizer personalizer,
            List<SourceAdapter> adapters,
            Clock clock) {
        this.watchlistRepository = watchlistRepository;
        this.subjectRepository = subjectRepository;
        this.personalizer = personalizer;
        this.adapters =
                adapters.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        SourceAdapter::sourceCode, Function.identity()));
        this.clock = clock;
    }

    /**
     * 装配每日推荐上下文 Map（对齐 Spike-2 §7.4 + T29 prompt v1.1 占位符）。
     *
     * <p>键：{@code poolSize}/{@code subjectsMetrics}/{@code subscribedThemes}/{@code
     * subscribedSubjects}/{@code readingProfile}/{@code today}。供 {@link AIBriefService} 合并进 prompt
     * 渲染上下文。
     *
     * @param userId 归属用户（行级权限取数键）
     * @return 上下文 Map（保序）；自选池空时 {@code poolSize=0}、其余占位填「暂无」
     */
    public Map<String, String> buildContext(long userId) {
        List<PoolMetric> metrics = buildPoolMetrics(userId);
        UserInterestProfile profile = personalizer.buildProfile(userId);
        return toContext(metrics, profile);
    }

    /**
     * 装配自选池指标列表（规则兜底排序用，复用 adapter 缓存）。
     *
     * @param userId 归属用户
     * @return 指标列表（去重保序）；自选池空时为空列表
     */
    public List<PoolMetric> buildPoolMetrics(long userId) {
        List<Long> subjectIds = distinctActiveSubjectIds(userId);
        if (subjectIds.isEmpty()) {
            return List.of();
        }
        SourceAdapter quote = adapters.get(SourceCode.QUOTE);
        SourceAdapter announce = adapters.get(SourceCode.ANNOUNCE);
        SourceAdapter news = adapters.get(SourceCode.NEWS);
        List<PoolMetric> metrics = new ArrayList<>(subjectIds.size());
        for (Long subjectId : subjectIds) {
            Subject subject = subjectRepository.findById(subjectId).orElse(null);
            if (subject == null) {
                log.warn("每日推荐：标的不存在，跳过 subjectId={}", subjectId);
                continue;
            }
            metrics.add(metricOf(subject, quote, announce, news));
        }
        return List.copyOf(metrics);
    }

    /** 装配上下文 Map（指标 + 画像 → 占位符投影）。 */
    private Map<String, String> toContext(List<PoolMetric> metrics, UserInterestProfile profile) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("poolSize", String.valueOf(metrics.size()));
        ctx.put("subjectsMetrics", formatMetrics(metrics));
        ctx.put("subscribedThemes", formatThemes(profile));
        ctx.put("subscribedSubjects", formatSubscribedSubjects(profile));
        ctx.put("readingProfile", formatReadingProfile(profile));
        ctx.put("today", LocalDate.now(clock).toString());
        return ctx;
    }

    /** 单只标的指标：取行情/公告/新闻 adapter（源降级归 0，不阻断）；T29 附 subjectId/industry。 */
    private PoolMetric metricOf(
            Subject subject, SourceAdapter quote, SourceAdapter announce, SourceAdapter news) {
        double changePct = extractChangePct(quote, subject);
        int announceCount = countItems(announce, subject);
        int newsCount = countItems(news, subject);
        return new PoolMetric(
                subject.getSubjectCode().value(),
                subject.getName(),
                changePct,
                announceCount,
                newsCount,
                subject.getId(),
                subject.getIndustry());
    }

    /** 行情涨跌幅（源降级/缺字段 → 0）。 */
    private double extractChangePct(SourceAdapter quote, Subject subject) {
        if (quote == null) {
            return 0.0;
        }
        try {
            SourceResult result = quote.fetch(subject);
            if (result.getStatus() != SourceStatus.OK) {
                return 0.0;
            }
            return toDouble(result.getData().get("changePct"));
        } catch (Exception e) {
            log.warn(
                    "每日推荐取行情异常 subjectCode={}: {}", subject.getSubjectCode().value(), e.toString());
            return 0.0;
        }
    }

    /** 公告/新闻条数（取 data.items 列表长度；源降级 → 0）。 */
    private int countItems(SourceAdapter adapter, Subject subject) {
        if (adapter == null) {
            return 0;
        }
        try {
            SourceResult result = adapter.fetch(subject);
            if (result.getStatus() != SourceStatus.OK) {
                return 0;
            }
            Object items = result.getData().get("items");
            return items instanceof List<?> list ? list.size() : 0;
        } catch (Exception e) {
            log.warn(
                    "每日推荐取列表源异常 sourceCode={} subjectCode={}: {}",
                    adapter.sourceCode(),
                    subject.getSubjectCode().value(),
                    e.toString());
            return 0;
        }
    }

    /** subjectsMetrics 投影：每行「代码|名称|涨跌幅X%|公告N|新闻M」（Spike-2 §7.4 指标快照）。 */
    private static String formatMetrics(List<PoolMetric> metrics) {
        if (metrics.isEmpty()) {
            return NA;
        }
        StringBuilder sb = new StringBuilder();
        for (PoolMetric m : metrics) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.subjectCode())
                    .append('|')
                    .append(m.subjectName())
                    .append("|涨跌幅")
                    .append(m.changePct())
                    .append("%|公告")
                    .append(m.announceCount())
                    .append("|新闻")
                    .append(m.newsCount());
        }
        return sb.toString();
    }

    /** subscribedThemes 投影：真实订阅主题词（T29 修复 T23「暂无」桩）；空→「暂无」。 */
    private static String formatThemes(UserInterestProfile profile) {
        if (profile.themeKeywords().isEmpty()) {
            return NA;
        }
        return String.join("、", profile.themeKeywords());
    }

    /** subscribedSubjects 投影：每行「代码|名称」（代码缺失用 id）；空→「暂无」。 */
    private static String formatSubscribedSubjects(UserInterestProfile profile) {
        if (profile.subscribedSubjects().isEmpty()) {
            return NA;
        }
        StringBuilder sb = new StringBuilder();
        for (UserInterestProfile.SubscribedSubject s : profile.subscribedSubjects()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s.code() != null ? s.code() : ("subjectId=" + s.subjectId()))
                    .append('|')
                    .append(s.name() != null ? s.name() : "");
        }
        return sb.toString();
    }

    /** readingProfile 投影：每行「代码|名称|阅读N次|最近阅读日期」（T29 近 30 天画像）；空→「暂无」。 */
    private static String formatReadingProfile(UserInterestProfile profile) {
        if (profile.readStats().isEmpty()) {
            return NA;
        }
        StringBuilder sb = new StringBuilder();
        for (UserInterestProfile.SubjectReadStat stat : profile.readStats()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(stat.code() != null ? stat.code() : ("subjectId=" + stat.subjectId()))
                    .append('|')
                    .append(stat.name() != null ? stat.name() : "")
                    .append("|阅读")
                    .append(stat.count())
                    .append("次|最近阅读")
                    .append(stat.lastReadDate());
        }
        return sb.toString();
    }

    /** 自选池活跃 subjectId 去重保序（跨该用户全部启用 watchlist 的启用清单项）。 */
    private List<Long> distinctActiveSubjectIds(long userId) {
        List<Watchlist> watchlists = watchlistRepository.findAllByOwnerId(userId);
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Watchlist w : watchlists) {
            w.getItems().stream()
                    .filter(i -> i.getStatus() == WatchlistStatus.ENABLED)
                    .map(WatchlistItem::getSubjectId)
                    .filter(seen::add)
                    .forEach(ids::add);
        }
        return ids;
    }

    /** 数值兼容转 double（BigDecimal/Number/字符串，非数→0）。 */
    private static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** T46：本装配器仅服务场景 4（每日推荐）。 */
    @Override
    public Set<BriefType> briefTypes() {
        return Set.of(BriefType.DAILY_RECOMMEND);
    }

    /** T46：注册表读取实际注入清单（与 {@code ctx.put} 同源，防漂移闸门见同源单测）。 */
    @Override
    public List<PlaceholderDescriptor> provided() {
        return PLACEHOLDERS;
    }
}
