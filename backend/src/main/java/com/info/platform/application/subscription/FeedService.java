package com.info.platform.application.subscription;

import com.info.platform.application.ai.DailyRecommendationResult;
import com.info.platform.application.ai.DailyRecommendationService;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 个人信息流应用服务（T27，对齐技术方案 §4.1.6 + PRD 故事 5）。
 *
 * <p>{@code getPersonalFeed(userId, cursor)} 编排：取用户活跃订阅 → 取近期内容（公告 T05/新闻 T06 按订阅标的 fetch + 政策 T24
 * 近期）→ {@link FeedMatcher} 匹配 → 合并每日推荐（T23）→ publishedAt 倒序 + 合成游标 id 分页 （{@code id > cursor LIMIT
 * 20}）→ {@link FeedListView}。
 *
 * <h2>行级权限（对齐 §5 安全）</h2>
 *
 * 取订阅走 {@link SubscriptionRepository#findByOwnerIdCursor}（带 {@code WHERE user_id=?}）而非 {@link
 * SubscriptionRepository#findAllActive}——后者按其端口 javadoc 为「系统任务专用、不走行级权限」（跨全部用户），
 * 不适用于面向用户的请求。退订过滤在此处先按 {@link Subscription#isActive} 过滤（status=0 不入流，PRD 故事 5 场景 3）， {@link
 * FeedMatcher} 入口再过滤一次（双层防御）。
 *
 * <h2>内容取数</h2>
 *
 * 公告/新闻按<b>标的订阅</b>的标的 fetch（T05/T06 {@code fetch(Subject)}，每源带缓存+弹性降级）；政策取近期 （{@link
 * PolicyRepository#findRecent}，newest-first）供主题/政策主题/事件类型/标的(行业)订阅匹配。
 * 主题订阅仅命中已取内容（公告/新闻按标的取，故主题订阅需用户另有标的订阅才有公告/新闻可命中——M2 已知限制， M3 可加关键词取数）。单源 MISSING/FAILED
 * 不阻断（降级分区不进流）。
 *
 * <h2>每日推荐（P1-5a 只读）</h2>
 *
 * 只读 {@link DailyRecommendationService#readDaily}（当日幂等缓存）：已终态成功 → Top5 映射为 {@code
 * type=recommendation} 条目（publishedAt 取装配时刻，排序靠前）；未触发/在途（PENDING）→ <b>不等待、不受理</b>，条目缺推荐项并置 {@code
 * recommendationPending=true}（FeedListView 增量字段）；FAILED/空输出 → 规则兜底（池指标并行取数）。触发生成的职责留给 {@code GET
 * /recommendations/daily} 与盘前预热 Job——feed 每页请求只做一次幂等键 SELECT（毫秒级），不再有 30s 轮询阻塞 与每页重复触发。
 *
 * <h2>分页</h2>
 *
 * M2 首期同步、内存合并：合并后按 publishedAt 倒序排，赋合成游标 id（1 起递增），按 {@code id > cursor LIMIT 20} 分页。 合成 id
 * 每次请求重算，跨请求稳定性（新内容到达致 id 漂移）待 M3 预计算落库后由持久 id 承接（对齐任务「首期同步足够」）。
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /** 游标分页页大小（对齐 §4.4 游标分页 LIMIT 20）。 */
    private static final int PAGE_SIZE = 20;

    /** 取用户订阅的单页上限（订阅量小，单页取全；对齐 §5 容量 watchlist ≤200/用户量级）。 */
    private static final int SUB_FETCH_LIMIT = 200;

    /** 政策匹配时间窗（天）。 */
    private static final int POLICY_DAYS = 7;

    /** 政策取数上限（匹配窗口，政策周量级远低于此）。 */
    private static final int POLICY_LIMIT = 50;

    /** 内容时间归属时区（与新闻 adapter ctime 落地 Asia/Shanghai 一致）。 */
    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Comparator<FeedEntry> COMPARATOR =
            Comparator.comparing(
                            FeedEntry::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(FeedEntry::sortType)
                    .thenComparing(FeedEntry::contentId);

    private final SubscriptionRepository subscriptionRepository;
    private final SubjectRepository subjectRepository;
    private final PolicyRepository policyRepository;
    private final DailyRecommendationService dailyRecommendationService;
    private final FeedMatcher feedMatcher;
    private final SourceAdapter announceAdapter;
    private final SourceAdapter newsAdapter;

    public FeedService(
            SubscriptionRepository subscriptionRepository,
            SubjectRepository subjectRepository,
            PolicyRepository policyRepository,
            DailyRecommendationService dailyRecommendationService,
            FeedMatcher feedMatcher,
            List<SourceAdapter> adapters) {
        this.subscriptionRepository = subscriptionRepository;
        this.subjectRepository = subjectRepository;
        this.policyRepository = policyRepository;
        this.dailyRecommendationService = dailyRecommendationService;
        this.feedMatcher = feedMatcher;
        this.announceAdapter = findByCode(adapters, SourceCode.ANNOUNCE);
        this.newsAdapter = findByCode(adapters, SourceCode.NEWS);
    }

    /**
     * 个人信息流（命中内容 + 每日推荐，游标分页）。
     *
     * <p>P1-5a：每日推荐走 {@link DailyRecommendationService#readDaily} <b>只读当日缓存</b>——未触发/在途不等待、不受理（避免
     * {@code generateDaily} 的 30s 轮询阻塞请求线程与每页重复触发），未就绪时返回增量标志 {@code
     * recommendationPending}（条目照常返回，仅缺推荐项）。
     *
     * @param userId 当前用户（行级权限取数键）
     * @param cursor 上一页末条合成 id；null/0 表首页
     */
    public FeedListView getPersonalFeed(long userId, Long cursor) {
        DailyRecommendationResult recommendation = dailyRecommendationService.readDaily(userId);
        List<Subscription> activeSubs = activeSubscriptions(userId);
        if (activeSubs.isEmpty()) {
            log.info("个人信息流无活跃订阅 userId={} 仅返回每日推荐", userId);
            return paginate(
                    recommendationEntries(recommendation),
                    cursor,
                    recommendation.recommendationPending());
        }
        Map<Long, Subject> subjects = resolveSubjects(activeSubs);
        Map<Long, SubjectRef> subjectIndex = buildSubjectIndex(subjects);
        List<FeedContent> contents = collectContents(activeSubs, subjects);
        List<MatchedFeedContent> matched = feedMatcher.match(activeSubs, contents, subjectIndex);
        List<FeedEntry> entries = new ArrayList<>(matched.size() + 5);
        for (MatchedFeedContent m : matched) {
            entries.add(toEntry(m));
        }
        entries.addAll(recommendationEntries(recommendation));
        log.info(
                "个人信息流装配 userId={} 活跃订阅={} 命中={} 条目={} 推荐未就绪={}",
                userId,
                activeSubs.size(),
                matched.size(),
                entries.size(),
                recommendation.recommendationPending());
        return paginate(entries, cursor, recommendation.recommendationPending());
    }

    /** 取当前用户活跃订阅（行级 {@code WHERE user_id=?} + status=1 过滤）。 */
    private List<Subscription> activeSubscriptions(long userId) {
        List<Subscription> all =
                subscriptionRepository.findByOwnerIdCursor(userId, null, null, SUB_FETCH_LIMIT);
        List<Subscription> active = new ArrayList<>();
        for (Subscription sub : all) {
            if (sub.isActive()) {
                active.add(sub);
            }
        }
        return active;
    }

    /** 解析标的订阅 subKey → Subject（fetch 公告/新闻与命中上下文共用）。 */
    private Map<Long, Subject> resolveSubjects(List<Subscription> activeSubs) {
        Map<Long, Subject> subjects = new HashMap<>();
        for (Subscription sub : activeSubs) {
            if (sub.getSubType() != SubscriptionType.SUBJECT) {
                continue;
            }
            Long subjectId = parseSubjectId(sub.getSubKey());
            if (subjectId == null) {
                continue;
            }
            subjectRepository.findById(subjectId).ifPresent(s -> subjects.put(subjectId, s));
        }
        return subjects;
    }

    /** 组装标的命中上下文（subjectId → SubjectRef：code/name/industry）。 */
    private static Map<Long, SubjectRef> buildSubjectIndex(Map<Long, Subject> subjects) {
        Map<Long, SubjectRef> index = new HashMap<>();
        for (Map.Entry<Long, Subject> e : subjects.entrySet()) {
            Subject s = e.getValue();
            index.put(
                    e.getKey(), new SubjectRef(s.getId(), codeOf(s), s.getName(), s.getIndustry()));
        }
        return index;
    }

    /** 取近期内容：标的订阅的公告/新闻 + 近期政策。 */
    private List<FeedContent> collectContents(
            List<Subscription> activeSubs, Map<Long, Subject> subjects) {
        List<FeedContent> contents = new ArrayList<>();
        for (Subscription sub : activeSubs) {
            if (sub.getSubType() != SubscriptionType.SUBJECT) {
                continue;
            }
            Long subjectId = parseSubjectId(sub.getSubKey());
            Subject subject = subjectId == null ? null : subjects.get(subjectId);
            if (subject == null) {
                continue;
            }
            contents.addAll(announceContents(subject));
            contents.addAll(newsContents(subject));
        }
        contents.addAll(policyContents());
        return contents;
    }

    /** 公告内容（T05 按标的 fetch；MISSING/FAILED/源未装配 不进流）。 */
    private List<FeedContent> announceContents(Subject subject) {
        if (announceAdapter == null) {
            return List.of();
        }
        SourceResult result = announceAdapter.fetch(subject);
        List<Map<String, Object>> items = itemsOf(result);
        if (items.isEmpty()) {
            return List.of();
        }
        List<FeedContent> out = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            out.add(
                    new FeedContent(
                            FeedItemType.ANNOUNCE,
                            str(item, "externalId"),
                            str(item, "title"),
                            null,
                            parsePublishedAt(str(item, "publishedAt")),
                            "公告",
                            str(item, "url"),
                            subject.getId(),
                            codeOf(subject),
                            subject.getName(),
                            List.of(),
                            str(item, "category")));
        }
        return out;
    }

    /** 新闻内容（T06 按标的 fetch；MISSING/FAILED/源未装配 不进流）。 */
    private List<FeedContent> newsContents(Subject subject) {
        if (newsAdapter == null) {
            return List.of();
        }
        SourceResult result = newsAdapter.fetch(subject);
        List<Map<String, Object>> items = itemsOf(result);
        if (items.isEmpty()) {
            return List.of();
        }
        List<FeedContent> out = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            out.add(
                    new FeedContent(
                            FeedItemType.NEWS,
                            str(item, "externalId"),
                            str(item, "title"),
                            str(item, "summary"),
                            parsePublishedAt(str(item, "publishedAt")),
                            str(item, "source"),
                            str(item, "url"),
                            subject.getId(),
                            codeOf(subject),
                            subject.getName(),
                            List.of(),
                            null));
        }
        return out;
    }

    /** 近期政策内容（T24，newest-first，匹配窗口 POLICY_LIMIT 条）。 */
    private List<FeedContent> policyContents() {
        List<PolicyItem> policies =
                policyRepository.findRecent(POLICY_DAYS, null, null, POLICY_LIMIT);
        List<FeedContent> out = new ArrayList<>(policies.size());
        for (PolicyItem p : policies) {
            out.add(
                    new FeedContent(
                            FeedItemType.POLICY,
                            String.valueOf(p.getId()),
                            p.getTitle(),
                            p.getSummary(),
                            policyInstant(p.getPublishedAt()),
                            p.getSource(),
                            p.getSourceUrl(),
                            null,
                            null,
                            null,
                            p.getRelatedIndustries(),
                            null));
        }
        return out;
    }

    /** 每日推荐条目（T23 Top5，type=recommendation，publishedAt 取装配时刻；P1-5a 只读结果投影，未就绪时为空列表）。 */
    private List<FeedEntry> recommendationEntries(DailyRecommendationResult rec) {
        Instant now = Instant.now();
        List<FeedEntry> out = new ArrayList<>(rec.topRecommend().size());
        for (TopRecommendation r : rec.topRecommend()) {
            FeedItem item =
                    new FeedItem(
                            0L,
                            FeedItemType.RECOMMENDATION,
                            r.subjectName(),
                            r.reason(),
                            iso(now),
                            "每日推荐",
                            null,
                            r.subjectCode(),
                            r.subjectName(),
                            "每日推荐",
                            List.of());
            out.add(new FeedEntry(now, "recommendation", "rec-" + r.subjectCode(), item));
        }
        return out;
    }

    /** 命中内容 → 排序条目（publishedAt 为排序键，合成 id 待分页回填；keywords 与命中原因同源）。 */
    private static FeedEntry toEntry(MatchedFeedContent matched) {
        FeedContent c = matched.content();
        FeedItem item =
                new FeedItem(
                        0L,
                        c.type(),
                        c.title(),
                        c.summary(),
                        iso(c.publishedAt()),
                        c.source(),
                        c.url(),
                        c.subjectCode(),
                        c.subjectName(),
                        matched.matchReason(),
                        matched.keywords());
        return new FeedEntry(c.publishedAt(), c.type().jsonValue(), c.contentId(), item);
    }

    /** publishedAt 倒序排序 + 合成游标 id 分页（{@code id > cursor LIMIT 20}）；透出推荐未就绪标志（P1-5a）。 */
    private FeedListView paginate(
            List<FeedEntry> entries, Long cursor, boolean recommendationPending) {
        entries.sort(COMPARATOR);
        long after = cursor == null ? 0L : cursor;
        List<FeedItem> page = new ArrayList<>();
        long lastId = 0L;
        long seq = 0L;
        for (FeedEntry e : entries) {
            seq++;
            if (seq <= after) {
                continue;
            }
            if (page.size() >= PAGE_SIZE) {
                break;
            }
            page.add(e.item().withId(seq));
            lastId = seq;
        }
        Long nextCursor = (page.size() == PAGE_SIZE && seq < entries.size()) ? lastId : null;
        return new FeedListView(page, nextCursor, recommendationPending);
    }

    /** 从全部 SourceAdapter 中取指定源（mock/真实互斥装配，恰一个；缺失记 WARN 返回 null）。 */
    private SourceAdapter findByCode(List<SourceAdapter> adapters, SourceCode code) {
        for (SourceAdapter a : adapters) {
            if (a.sourceCode() == code) {
                return a;
            }
        }
        log.warn("信息流未找到源适配器 code={}（降级：该源内容不进流）", code);
        return null;
    }

    // —— 工具方法 ——

    /** SourceResult.data["items"] → 规范化 Map 列表（OK 且有 items 才返回非空）。 */
    private static List<Map<String, Object>> itemsOf(SourceResult result) {
        if (result.getStatus() != SourceStatus.OK) {
            return List.of();
        }
        Object items = result.getData().get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> cast = new LinkedHashMap<>();
                m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                out.add(cast);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String codeOf(Subject subject) {
        return subject.getSubjectCode() == null ? null : subject.getSubjectCode().value();
    }

    /** 解析映射后的 ISO 时间串为 Instant：支持 {@code yyyy-MM-dd} 与 {@code yyyy-MM-dd'T'HH:mm:ss}。 */
    static Instant parsePublishedAt(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            if (iso.contains("T")) {
                return LocalDateTime.parse(iso).atZone(FEED_ZONE).toInstant();
            }
            return LocalDate.parse(iso).atStartOfDay(FEED_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant policyInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(FEED_ZONE).toInstant();
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** 解析标的订阅 subKey 为 subjectId；非数字返回 null（跳过，不阻断）。 */
    private static Long parseSubjectId(String subKey) {
        if (subKey == null || subKey.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(subKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 排序/分页内部条目（publishedAt 排序键 + 稳定副键 + 待回填 id 的 FeedItem）。 */
    private record FeedEntry(
            Instant publishedAt, String sortType, String contentId, FeedItem item) {}
}
