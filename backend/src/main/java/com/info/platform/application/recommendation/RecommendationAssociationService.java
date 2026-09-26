package com.info.platform.application.recommendation;

import com.info.platform.application.ai.RecommendationPersonalizer;
import com.info.platform.application.ai.UserInterestProfile;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.recommendation.IndustryDirectory;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard.CardSubject;
import com.info.platform.domain.recommendation.RecommendationScoreCalculator;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 三级关联引擎（应用层，M16 T131，方案 §4.4 / ADR-0051 裁决 4）：事件 × 用户关注配置 → 关联结果（或空）。
 *
 * <p><b>触发门槛</b>：{@code importance ∈ {HIGH, MEDIUM}}（LOW 永不触发，红线）∧ 三级关联命中非空——无命中返回空（不生成卡片、零留痕；
 * 事件流可查）。逐级判定，命中多级取最高（P1 &gt; P2 &gt; P3）。
 *
 * <ul>
 *   <li><b>P1 标的直接</b>：{@code event.subjects[].code ∩（自选 watchlist ∪ SUBJECT 订阅）标的代码集}（ADR-0051
 *       裁决延伸： 显式标的订阅并入 P1）；标的区 = 命中标的（inWatchlist=true）。
 *   <li><b>P2 行业</b>（P1 未命中时）：{@code event.affectedIndustries ∩ 行业关注集}，关注集 = 双通道并集——通道 A（订阅主题经
 *       {@link IndustryDirectory} 映射，v1 主力）∪ 通道 B（自选 ∪ SUBJECT 订阅标的的 subject_master.industry 非空者
 *       best-effort， NULL 跳过不阻断，东财主链恢复同步即自动增强）；标的区 = 关注标的中行业 ∈ 命中集者（≤5，可为空——通道 B 空转时不硬凑）。
 *   <li><b>P3 订阅</b>（P1/P2 未命中时）：EVENT_TYPE 订阅折算（subKey 含 公告/政策/新闻 或 = ANNOUNCE/NEWS/POLICY 枚举名，沿
 *       FeedMatcher 口径）或 TOPIC/POLICY_THEME 主题词 contains 命中事件标题/摘要；标的区 = 空（红线：不硬凑标的）。
 * </ul>
 *
 * <p><b>画像只加权不触发</b>：{@link UserInterestProfile} 仅进 profileCoef（recscore-v1），触发链无画像输入。
 */
@Service
public class RecommendationAssociationService {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationAssociationService.class);

    /** 取用户订阅的单页上限（订阅量小，单页取全——沿 RecommendationPersonalizer 惯例）。 */
    private static final int SUB_FETCH_LIMIT = 200;

    /** EVENT_TYPE 折算：含「公告」/枚举名 ANNOUNCE → 公司类事件 6 类（方案 §4.4 P3a）。 */
    private static final Set<EventType> ANNOUNCE_TYPES =
            Set.of(
                    EventType.EARNINGS_FORECAST,
                    EventType.MA_MERGER,
                    EventType.BUYBACK_CHANGE,
                    EventType.MAJOR_CONTRACT,
                    EventType.EXEC_CHANGE,
                    EventType.REGULATORY_PENALTY);

    /** EVENT_TYPE 折算：含「新闻」/枚举名 NEWS → 全部 9 类。 */
    private static final Set<EventType> ALL_TYPES = Set.copyOf(List.of(EventType.values()));

    private final WatchlistRepository watchlistRepository;

    private final SubscriptionRepository subscriptionRepository;

    private final SubjectRepository subjectRepository;

    private final RecommendationPersonalizer personalizer;

    private final RecommendationSettings settings;

    public RecommendationAssociationService(
            WatchlistRepository watchlistRepository,
            SubscriptionRepository subscriptionRepository,
            SubjectRepository subjectRepository,
            RecommendationPersonalizer personalizer,
            RecommendationSettings settings) {
        this.watchlistRepository = watchlistRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subjectRepository = subjectRepository;
        this.personalizer = personalizer;
        this.settings = settings;
    }

    /**
     * 装配用户关联上下文（每用户每 tick 一次，逐事件复用——方案 §4.4）。
     *
     * @param userId 归属用户（行级权限取数键）
     */
    public AssociationContext buildContext(long userId) {
        Set<Long> watchSubjectIds = new LinkedHashSet<>();
        for (Watchlist list : watchlistRepository.findAllByOwnerId(userId)) {
            if (list.getStatus() != WatchlistStatus.ENABLED) {
                continue;
            }
            for (var item : list.getItems()) {
                if (item.getStatus() == WatchlistStatus.ENABLED && item.getSubjectId() != null) {
                    watchSubjectIds.add(item.getSubjectId());
                }
            }
        }
        Set<String> themes = new LinkedHashSet<>();
        List<String> eventTypeSubKeys = new ArrayList<>();
        for (Subscription sub :
                subscriptionRepository.findByOwnerIdCursor(userId, null, null, SUB_FETCH_LIMIT)) {
            if (!sub.isActive()) {
                continue;
            }
            switch (sub.getSubType()) {
                case TOPIC, POLICY_THEME -> {
                    if (sub.getSubKey() != null && !sub.getSubKey().isBlank()) {
                        themes.add(sub.getSubKey().trim());
                    }
                }
                case SUBJECT -> {
                    Long subjectId = parseSubjectId(sub.getSubKey());
                    if (subjectId != null) {
                        watchSubjectIds.add(subjectId); // P1 判定集并入 SUBJECT 订阅（ADR-0051）
                    }
                }
                case EVENT_TYPE -> eventTypeSubKeys.add(sub.getSubKey());
                default -> log.debug("未识别订阅类型跳过: userId={} subType={}", userId, sub.getSubType());
            }
        }
        Map<Long, Subject> subjectsById =
                subjectRepository.findAllById(watchSubjectIds).stream()
                        .collect(
                                Collectors.toMap(Subject::getId, Function.identity(), (a, b) -> a));
        Map<String, WatchedSubject> watchByCode = new LinkedHashMap<>();
        for (Long subjectId : watchSubjectIds) {
            Subject subject = subjectsById.get(subjectId);
            if (subject == null || subject.getSubjectCode() == null) {
                continue; // 已删/无代码标的跳过（不阻断）
            }
            watchByCode.putIfAbsent(
                    subject.getSubjectCode().value(),
                    new WatchedSubject(
                            subjectId,
                            subject.getSubjectCode().value(),
                            subject.getName(),
                            subject.getIndustry()));
        }
        // P2 行业关注集：通道 A（订阅主题映射，v1 主力）∪ 通道 B（标的行业 best-effort，NULL 跳过）
        Set<String> industryInterest = new LinkedHashSet<>();
        for (String theme : themes) {
            industryInterest.addAll(IndustryDirectory.industriesOf(theme));
        }
        for (WatchedSubject watched : watchByCode.values()) {
            if (watched.industry() != null && !watched.industry().isBlank()) {
                industryInterest.add(watched.industry());
            }
        }
        UserInterestProfile profile = personalizer.buildProfile(userId);
        return new AssociationContext(
                userId,
                List.copyOf(watchByCode.values()),
                Set.copyOf(themes),
                List.copyOf(eventTypeSubKeys),
                Collections.unmodifiableSet(industryInterest),
                profile);
    }

    /**
     * 逐事件判定（纯函数式，无 IO——输入上下文装配一次，逐事件复用）。
     *
     * @param event 结构化事件（L2 产物）
     * @param newsTitle 原文标题（P3 主题词 contains 命中面；可空）
     * @param ctx 用户关联上下文
     * @return 关联结果；LOW / 无命中返回空（不生成卡片零留痕）
     */
    public Optional<AssociationResult> associate(
            EventItem event, String newsTitle, AssociationContext ctx) {
        if (event.getImportance() != Importance.HIGH
                && event.getImportance() != Importance.MEDIUM) {
            return Optional.empty(); // LOW 永不触发（红线）
        }
        Map<String, Double> heatByCode = heatByCode(ctx.profile());
        Map<String, WatchedSubject> watchByCode = ctx.watchByCode();
        String matchedTheme = matchedTheme(event, newsTitle, ctx);
        boolean themeHit = matchedTheme != null;

        // P1 标的直接
        List<CardSubject> p1Hits = new ArrayList<>();
        for (EventItem.SubjectRef subject : event.getSubjects()) {
            if (subject.code() != null && watchByCode.containsKey(subject.code())) {
                p1Hits.add(
                        new CardSubject(subject.code(), subject.name(), subject.industry(), true));
            }
        }
        if (!p1Hits.isEmpty()) {
            return Optional.of(
                    result(
                            event,
                            RecLevel.P1,
                            event.getAffectedIndustries(),
                            p1Hits,
                            themeHit,
                            matchedTheme,
                            heatByCode));
        }

        // P2 行业（双通道关注集）
        List<String> industryHits = new ArrayList<>();
        for (String industry : event.getAffectedIndustries()) {
            if (ctx.industryInterest().contains(industry)) {
                industryHits.add(industry);
            }
        }
        if (!industryHits.isEmpty()) {
            List<CardSubject> p2Subjects = new ArrayList<>();
            for (WatchedSubject watched : ctx.watchSubjects()) {
                if (watched.industry() != null && industryHits.contains(watched.industry())) {
                    p2Subjects.add(
                            new CardSubject(
                                    watched.code(), watched.name(), watched.industry(), true));
                }
            }
            return Optional.of(
                    result(
                            event,
                            RecLevel.P2,
                            industryHits,
                            p2Subjects,
                            themeHit,
                            matchedTheme,
                            heatByCode));
        }

        // P3 订阅（EVENT_TYPE 折算 / 主题词 contains）
        if (eventTypeSubscribed(event.getEventType(), ctx.eventTypeSubKeys()) || themeHit) {
            return Optional.of(
                    result(
                            event,
                            RecLevel.P3,
                            event.getAffectedIndustries(),
                            List.of(),
                            themeHit,
                            matchedTheme,
                            heatByCode));
        }
        return Optional.empty();
    }

    /** 组装关联结果（标的区截断：P1 优先 → 画像热度降序，≤ cardSubjectLimit；combo_key 口径 §4.4）。 */
    private AssociationResult result(
            EventItem event,
            RecLevel level,
            List<String> industries,
            List<CardSubject> subjectArea,
            boolean themeHit,
            String matchedTheme,
            Map<String, Double> heatByCode) {
        List<CardSubject> sorted = new ArrayList<>(subjectArea);
        sorted.sort(
                Comparator.comparing((CardSubject s) -> heatByCode.getOrDefault(s.code(), 0.0))
                        .reversed());
        List<CardSubject> capped =
                List.copyOf(
                        sorted.subList(0, Math.min(sorted.size(), settings.cardSubjectLimit())));
        RecommendationScoreCalculator.ScoreParams params = settings.scoreParams();
        double maxHeat =
                capped.stream()
                        .mapToDouble(s -> heatByCode.getOrDefault(s.code(), 0.0))
                        .max()
                        .orElse(0.0);
        double recscore =
                RecommendationScoreCalculator.calculate(
                        level, event.getImportance(), maxHeat, themeHit, params);
        return new AssociationResult(
                level,
                List.copyOf(industries),
                capped,
                comboKey(event, level, industries),
                recscore,
                params.basis(),
                themeHit,
                matchedTheme);
    }

    /** combo_key = "{eventType}|{industry}"（P2 命中行业首个；否则 affectedIndustries 首个；空用 "-"）。 */
    private static String comboKey(EventItem event, RecLevel level, List<String> industries) {
        String industry =
                level == RecLevel.P2 && !industries.isEmpty()
                        ? industries.get(0)
                        : event.getAffectedIndustries().isEmpty()
                                ? "-"
                                : event.getAffectedIndustries().get(0);
        return event.getEventType().name() + "|" + industry;
    }

    /** 主题词命中：contains 命中标题/摘要，或主题映射行业 ∩ 事件影响行业（themeHitScore 语义，§4.4）。 */
    private static String matchedTheme(EventItem event, String newsTitle, AssociationContext ctx) {
        String text =
                (newsTitle == null ? "" : newsTitle)
                        + "\n"
                        + (event.getSummary() == null ? "" : event.getSummary());
        for (String theme : ctx.themeKeywords()) {
            if (theme.isBlank()) {
                continue;
            }
            if (containsIgnoreCase(text, theme)) {
                return theme;
            }
            for (String industry : IndustryDirectory.industriesOf(theme)) {
                if (event.getAffectedIndustries().contains(industry)) {
                    return theme;
                }
            }
        }
        return null;
    }

    /** EVENT_TYPE 订阅折算（沿 FeedMatcher 口径）：含 公告/政策/新闻 中文标签或 = ANNOUNCE/NEWS/POLICY 枚举名。 */
    private static boolean eventTypeSubscribed(EventType eventType, List<String> subKeys) {
        for (String subKey : subKeys) {
            if (subKey == null) {
                continue;
            }
            String key = subKey.trim();
            String lower = key.toLowerCase(Locale.ROOT);
            Set<EventType> types;
            if (key.contains("公告") || lower.equals("announce")) {
                types = ANNOUNCE_TYPES;
            } else if (key.contains("政策") || lower.equals("policy")) {
                types = Set.of(EventType.POLICY_RELEASE);
            } else if (key.contains("新闻") || lower.equals("news")) {
                types = ALL_TYPES;
            } else {
                continue;
            }
            if (types.contains(eventType)) {
                return true;
            }
        }
        return false;
    }

    /** 画像热度按标的代码索引（profileCoef 的 normSubjectHeat 输入；heat=Σ0.5^(距今天数/7)）。 */
    private static Map<String, Double> heatByCode(UserInterestProfile profile) {
        Map<String, Double> byCode = new LinkedHashMap<>();
        for (UserInterestProfile.SubjectReadStat stat : profile.readStats()) {
            if (stat.code() != null) {
                byCode.merge(stat.code(), stat.heat(), Math::max);
            }
        }
        return byCode;
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

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

    /** 用户关联上下文（每用户每 tick 装配一次；纯值对象）。 */
    public record AssociationContext(
            long userId,
            List<WatchedSubject> watchSubjects,
            Set<String> themeKeywords,
            List<String> eventTypeSubKeys,
            Set<String> industryInterest,
            UserInterestProfile profile) {

        /** 标的代码索引（P1 O(1) 判定）。 */
        public Map<String, WatchedSubject> watchByCode() {
            return watchSubjects().stream()
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    WatchedSubject::code, Function.identity()));
        }
    }

    /** 关注标的引用（自选 ∪ SUBJECT 订阅解析；industry 可空 = 通道 B 无数据）。 */
    public record WatchedSubject(Long subjectId, String code, String name, String industry) {}

    /** 关联结果（卡片生成输入：层级/命中行业/标的区/combo_key/recscore/basis/主题来源）。 */
    public record AssociationResult(
            RecLevel level,
            List<String> industries,
            List<CardSubject> subjects,
            String comboKey,
            double recscore,
            String basis,
            boolean themeHit,
            String matchedTheme) {}
}
