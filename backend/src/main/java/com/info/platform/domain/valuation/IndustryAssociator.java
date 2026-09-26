package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.IndustryCategory;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业关联派生器（领域纯函数，M20 ADR-0058 裁决 2——本批最关键裁决）：绕行 {@code subject_master.industry} （5221 只 100% NULL
 * 且随源轮换漂移、入因子会结构性破坏可复算幂等），从库内两路原料派生「标的 ↔ 行业」关联集：
 *
 * <ul>
 *   <li>路 A（事件回联）：窗内 {@code event_item.subjects} 含该标的 → {@code affected_industries} 全部记为关联行业（权重
 *       1.0）；
 *   <li>路 B（资讯归类回联）：窗内 {@code news_analysis.matched_subjects} 含该标的 → {@code main_category}（申万）
 *       记主关联（权重 1.0）、{@code sub_industry} 记次关联（权重 0.5）；容器 main 不记。
 * </ul>
 *
 * <p>窗口 W2（默认 30 天）锚定 snapshotDate；同 (标的, 行业) 多源融合取「权重最大 → 最近出现 → 来源优先
 * (EVENT&gt;NEWS_MAIN&gt;NEWS_SUB)」；输出按行业名排序确定性。空 code（事件未回联仅留名）跳过。
 */
public final class IndustryAssociator {

    private IndustryAssociator() {}

    /** 路 A 原料：事件回联行（subjects 代码 + 受影响行业 + 事件日）。 */
    public record EventLink(
            List<String> subjectCodes, List<String> affectedIndustries, LocalDate eventDate) {}

    /** 路 B 原料：资讯归类回联行（matched 代码 + 主/次行业 + 发布日）。 */
    public record NewsLink(
            List<String> subjectCodes,
            String mainCategory,
            String subIndustry,
            LocalDate publishedDate) {}

    /** 关联来源（detail 落串：EVENT / NEWS_MAIN / NEWS_SUB）。 */
    public enum Source {
        EVENT(3),
        NEWS_MAIN(2),
        NEWS_SUB(1);

        final int priority;

        Source(int priority) {
            this.priority = priority;
        }
    }

    /** 单条关联（weight：主 1.0 / 次 0.5；lastSeenAgeDays：距 snapshotDate 的日历日）。 */
    public record Association(
            String industry, double weight, long lastSeenAgeDays, Source source) {}

    /**
     * 派生全部标的的关联集。
     *
     * @param events W2 窗内事件回联行（越窗行防御性跳过）
     * @param news W2 窗内资讯回联行（越窗行防御性跳过）
     * @param snapshotDate 快照口径日（窗口与 age 锚点）
     * @param windowDays 关联窗 W2（天）
     * @return subjectCode → 关联列表（按权重降序、最近优先、行业名升序——无热度输入时的确定性序）
     */
    public static Map<String, List<Association>> associate(
            List<EventLink> events, List<NewsLink> news, LocalDate snapshotDate, int windowDays) {
        Map<String, Map<String, Association>> bySubject = new HashMap<>();
        for (EventLink link : events) {
            long age = ageOf(link.eventDate(), snapshotDate);
            if (age < 0 || age >= windowDays) {
                continue;
            }
            for (String code : codesOf(link.subjectCodes())) {
                for (String industry : industriesOf(link.affectedIndustries())) {
                    merge(
                            bySubject,
                            code,
                            industry,
                            new Association(industry, 1.0, age, Source.EVENT));
                }
            }
        }
        for (NewsLink link : news) {
            long age = ageOf(link.publishedDate(), snapshotDate);
            if (age < 0 || age >= windowDays) {
                continue;
            }
            for (String code : codesOf(link.subjectCodes())) {
                if (IndustryCategory.isSwIndustry(link.mainCategory())) {
                    merge(
                            bySubject,
                            code,
                            link.mainCategory(),
                            new Association(link.mainCategory(), 1.0, age, Source.NEWS_MAIN));
                }
                if (IndustryCategory.isSwIndustry(link.subIndustry())) {
                    merge(
                            bySubject,
                            code,
                            link.subIndustry(),
                            new Association(link.subIndustry(), 0.5, age, Source.NEWS_SUB));
                }
            }
        }
        return freeze(bySubject);
    }

    /** 融合规则：权重降序 → 最近出现 → 来源优先（确定性，不叠加——一标的一行业一关联）。 */
    private static void merge(
            Map<String, Map<String, Association>> bySubject,
            String code,
            String industry,
            Association candidate) {
        Association current =
                bySubject.computeIfAbsent(code, unused -> new HashMap<>()).get(industry);
        if (current == null || stronger(candidate, current)) {
            bySubject.get(code).put(industry, candidate);
        }
    }

    private static boolean stronger(Association candidate, Association current) {
        if (candidate.weight() != current.weight()) {
            return candidate.weight() > current.weight();
        }
        if (candidate.lastSeenAgeDays() != current.lastSeenAgeDays()) {
            return candidate.lastSeenAgeDays() < current.lastSeenAgeDays();
        }
        return candidate.source().priority > current.source().priority;
    }

    private static Map<String, List<Association>> freeze(
            Map<String, Map<String, Association>> bySubject) {
        Map<String, List<Association>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Association>> entry : bySubject.entrySet()) {
            List<Association> associations = new ArrayList<>(entry.getValue().values());
            associations.sort(
                    (a, b) -> {
                        int byWeight = Double.compare(b.weight(), a.weight());
                        if (byWeight != 0) {
                            return byWeight;
                        }
                        int byAge = Long.compare(a.lastSeenAgeDays(), b.lastSeenAgeDays());
                        if (byAge != 0) {
                            return byAge;
                        }
                        return a.industry().compareTo(b.industry());
                    });
            result.put(entry.getKey(), List.copyOf(associations));
        }
        return result;
    }

    private static List<String> codesOf(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream().filter(code -> code != null && !code.isBlank()).toList();
    }

    private static List<String> industriesOf(List<String> industries) {
        if (industries == null) {
            return List.of();
        }
        return industries.stream().filter(IndustryCategory::isSwIndustry).toList();
    }

    private static long ageOf(LocalDate date, LocalDate snapshotDate) {
        return ChronoUnit.DAYS.between(date, snapshotDate);
    }
}
