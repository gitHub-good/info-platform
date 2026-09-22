package com.info.platform.application.ai;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.ReadingEvent;
import com.info.platform.domain.ai.ReadingEventRepository;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用户兴趣画像装配器（应用层，T29）：订阅 + 近期阅读 → {@link UserInterestProfile}。
 *
 * <p>数据源：{@code subscription_config} 活跃订阅（主题词 = TOPIC + POLICY_THEME 的 subKey；标的订阅 = SUBJECT 的
 * subKey 解析 subjectId）+ {@code reading_event} 近 30 天留痕（按 subjectId 聚合，热度按 {@code 0.5^(距今天数/7)}
 * 时间衰减——一周前的阅读权重减半，防止陈旧兴趣长期主导排序）。
 *
 * <p>降级（对齐技术方案 §5「每日推荐用规则兜底」精神）：任一取数异常整画像退化为空（WARN 不上抛）——个性化是增强信号，装配失败不能拖垮推荐主链路；空画像下评分退化为既有活跃度排序。
 */
@Component
public class RecommendationPersonalizer {

    private static final Logger log = LoggerFactory.getLogger(RecommendationPersonalizer.class);

    /** 阅读画像时间窗（天）：PRD 场景 2「用户历史阅读记录」的可操作窗口。 */
    static final int PROFILE_DAYS = 30;

    /** 阅读热度半衰期（天）：0.5^(距今天数/7)。 */
    static final int READ_HALF_LIFE_DAYS = 7;

    /** 取用户订阅的单页上限（订阅量小，单页取全）。 */
    private static final int SUB_FETCH_LIMIT = 200;

    /** 阅读留痕取数上限（护栏；个人量级 30 天远低于此）。 */
    private static final int READ_FETCH_LIMIT = 500;

    private final SubscriptionRepository subscriptionRepository;
    private final ReadingEventRepository readingEventRepository;
    private final SubjectRepository subjectRepository;
    private final Clock clock;

    public RecommendationPersonalizer(
            SubscriptionRepository subscriptionRepository,
            ReadingEventRepository readingEventRepository,
            SubjectRepository subjectRepository,
            Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.readingEventRepository = readingEventRepository;
        this.subjectRepository = subjectRepository;
        this.clock = clock;
    }

    /**
     * 装配用户兴趣画像（订阅 + 近 30 天阅读）。
     *
     * @param userId 归属用户（行级权限取数键）
     * @return 画像；无订阅且无阅读或取数异常时为空画像（不抛）
     */
    public UserInterestProfile buildProfile(long userId) {
        try {
            List<Subscription> subs =
                    subscriptionRepository.findByOwnerIdCursor(userId, null, null, SUB_FETCH_LIMIT);
            List<ReadingEvent> reads =
                    readingEventRepository.findByUserSince(
                            userId,
                            Instant.now(clock).minus(Duration.ofDays(PROFILE_DAYS)),
                            READ_FETCH_LIMIT);
            return assemble(subs, reads);
        } catch (Exception e) {
            log.warn("用户画像装配失败，退化为空画像 userId={}: {}", userId, e.toString());
            return UserInterestProfile.EMPTY;
        }
    }

    /** 组装画像：订阅拆主题词/标的引用，阅读按标的聚合衰减热度。 */
    private UserInterestProfile assemble(List<Subscription> subs, List<ReadingEvent> reads) {
        Set<String> themes = new LinkedHashSet<>();
        Map<Long, UserInterestProfile.SubscribedSubject> subjectSubs = new LinkedHashMap<>();
        for (Subscription sub : subs) {
            if (!sub.isActive()) {
                continue;
            }
            collectSubscription(sub, themes, subjectSubs);
        }
        List<UserInterestProfile.SubjectReadStat> stats = aggregateReads(reads);
        return new UserInterestProfile(
                List.copyOf(themes), List.copyOf(subjectSubs.values()), stats);
    }

    /** 单订阅归类：TOPIC/POLICY_THEME → 主题词；SUBJECT → 标的引用（解析标的名称，失败跳过）。 */
    private void collectSubscription(
            Subscription sub,
            Set<String> themes,
            Map<Long, UserInterestProfile.SubscribedSubject> subjectSubs) {
        if (sub.getSubType() == SubscriptionType.TOPIC
                || sub.getSubType() == SubscriptionType.POLICY_THEME) {
            if (sub.getSubKey() != null && !sub.getSubKey().isBlank()) {
                themes.add(sub.getSubKey().trim());
            }
            return;
        }
        if (sub.getSubType() == SubscriptionType.SUBJECT) {
            Long subjectId = parseSubjectId(sub.getSubKey());
            if (subjectId != null && !subjectSubs.containsKey(subjectId)) {
                subjectSubs.put(subjectId, toSubscribedSubject(subjectId));
            }
        }
    }

    /** 标的订阅 → 引用（标的已删/解析失败记 null 代码占位，评分按 id 命中不受影响）。 */
    private UserInterestProfile.SubscribedSubject toSubscribedSubject(long subjectId) {
        String code = null;
        String name = null;
        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        if (subject != null) {
            code = subject.getSubjectCode() == null ? null : subject.getSubjectCode().value();
            name = subject.getName();
        }
        return new UserInterestProfile.SubscribedSubject(subjectId, code, name);
    }

    /** 阅读留痕按标的聚合：次数、最近阅读日期、衰减热度（无标的关联的阅读跳过）。 */
    private List<UserInterestProfile.SubjectReadStat> aggregateReads(List<ReadingEvent> reads) {
        Instant now = Instant.now(clock);
        Map<Long, Accumulator> bySubject = new LinkedHashMap<>();
        for (ReadingEvent event : reads) {
            if (event.getSubjectId() == null || event.getCreatedAt() == null) {
                continue;
            }
            Accumulator acc =
                    bySubject.computeIfAbsent(event.getSubjectId(), id -> new Accumulator(now));
            acc.add(event.getCreatedAt());
        }
        List<UserInterestProfile.SubjectReadStat> stats = new ArrayList<>(bySubject.size());
        for (Map.Entry<Long, Accumulator> e : bySubject.entrySet()) {
            stats.add(toReadStat(e.getKey(), e.getValue()));
        }
        stats.sort(
                Comparator.comparingDouble(UserInterestProfile.SubjectReadStat::heat).reversed());
        return stats;
    }

    /** 单标的统计（附标的代码/名称，已删标的为 null）。 */
    private UserInterestProfile.SubjectReadStat toReadStat(long subjectId, Accumulator acc) {
        String code = null;
        String name = null;
        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        if (subject != null) {
            code = subject.getSubjectCode() == null ? null : subject.getSubjectCode().value();
            name = subject.getName();
        }
        return new UserInterestProfile.SubjectReadStat(
                subjectId, code, name, acc.count, acc.lastReadDate, acc.heat);
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

    /** 聚合器：累计次数与衰减热度，追踪最近阅读日期。 */
    private static final class Accumulator {

        private final Instant now;
        private int count;
        private LocalDate lastReadDate;
        private double heat;

        private Accumulator(Instant now) {
            this.now = now;
        }

        private void add(Instant readAt) {
            count++;
            heat += decayWeight(readAt);
            LocalDate date = LocalDate.ofInstant(readAt, ZoneId.systemDefault());
            if (lastReadDate == null || date.isAfter(lastReadDate)) {
                lastReadDate = date;
            }
        }

        /** 时间衰减权重：0.5^(距今天数/7)（当天阅读=1，一周前=0.5，一个月前≈0.05）。 */
        private double decayWeight(Instant readAt) {
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(readAt, now));
            return Math.pow(0.5, (double) ageDays / READ_HALF_LIFE_DAYS);
        }
    }
}
