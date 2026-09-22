package com.info.platform.application.subscription;

import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 信息流命中引擎（应用层，T27，对齐技术方案 §4.1.6 + PRD 故事 5）。
 *
 * <p>纯函数匹配器：输入用户活跃订阅 + 统一内容 + 标的命中上下文（subjectId → {@link SubjectRef}），输出每条内容
 * 的首次命中原因。无状态、不依赖仓储/HTTP，可脱离容器单测（FeedServiceTest 直接 {@code new FeedMatcher()} 复用）。
 *
 * <h2>命中规则（按 sub_type）</h2>
 *
 * <ul>
 *   <li>{@link SubscriptionType#TOPIC 主题}(1)：内容标题或摘要 {@code contains} subKey（主题关键词）→ 命中。
 *       匹配全部内容类型（公告/新闻/政策）。
 *   <li>{@link SubscriptionType#SUBJECT 标的}(2)：内容关联该标的——公告/新闻按 {@code content.subjectId ==
 *       subjectId} （T05/T06 按标的 fetch，内容天然归属标的）；政策按 {@code content.relatedIndustries} 含 {@code
 *       subject.industry} （T24 政策行业标签）→ 命中。标的上下文缺失（subjectId 解析不到）→ 不命中。
 *   <li>{@link SubscriptionType#EVENT_TYPE 事件类型}(3)：内容类型匹配——subKey 等于内容类型枚举名（ANNOUNCE/NEWS/POLICY）
 *       或含其中文标签（公告/新闻/政策），或公告 {@code category} 含 subKey → 命中。异动事件走推送不入拉流（已知限制）。
 *   <li>{@link SubscriptionType#POLICY_THEME 政策主题}(4)：政策标题或摘要 {@code contains} subKey →
 *       命中；非政策内容不命中。
 * </ul>
 *
 * <h2>退订降噪</h2>
 *
 * status=0（已退订）的订阅经 {@link #match} 入口先按 {@link Subscription#isActive} 过滤，不参与匹配——对齐 PRD 故事 5 场景 3
 * 「退订后不推送且不在个人信息流展示」。FeedService 取订阅时亦按 active 过滤（双层防御）。
 *
 * <h2>去重与命中原因</h2>
 *
 * 一条内容命中任一订阅即入流（首次命中为准，break），命中原因串形如「主题订阅:半导体」/「标的订阅:贵州茅台」/ 「事件类型订阅:公告」/「政策主题订阅:货币政策」。多订阅同命中的聚合原因留
 * M3 优化。
 *
 * <h2>命中关键词（T43）</h2>
 *
 * 命中同时携带 {@code keywords}（与首次命中订阅同源）：主题/政策主题为 subKey（标题或摘要文本必现）；标的为标的展示名 （按
 * subjectId/行业命中，文本未必出现，由前端仅出现时高亮）；事件类型按类型命中无文本关键词（空列表）。 供 FeedItem {@code
 * keywords[]} 透出，前端命中词高亮用（UI 方案 §6.2 联判点 5）。
 *
 * <h2>可扩展</h2>
 *
 * 首期 {@code contains} + 行业关联；后续语义匹配/AI 命中可在本类内按 subType 扩展策略，不改调用方契约。
 */
@Component
public class FeedMatcher {

    /** 按订阅匹配内容，返回命中内容（含首次命中原因）。退订订阅不参与匹配。 */
    public List<MatchedFeedContent> match(
            List<Subscription> subscriptions,
            List<FeedContent> contents,
            Map<Long, SubjectRef> subjectIndex) {
        List<Subscription> active = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            if (sub.isActive()) {
                active.add(sub);
            }
        }
        List<MatchedFeedContent> matched = new ArrayList<>();
        for (FeedContent content : contents) {
            MatchHit hit = firstMatch(active, content, subjectIndex);
            if (hit != null) {
                matched.add(new MatchedFeedContent(content, hit.reason(), hit.keywords()));
            }
        }
        return matched;
    }

    /** 遍历订阅，返回首个命中（原因 + 关键词）；无命中返回 null。 */
    private MatchHit firstMatch(
            List<Subscription> active, FeedContent content, Map<Long, SubjectRef> subjectIndex) {
        for (Subscription sub : active) {
            MatchHit hit = matchHit(sub, content, subjectIndex);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 单订阅对单内容的命中判定，命中返回原因+关键词，否则 null。 */
    private MatchHit matchHit(
            Subscription sub, FeedContent content, Map<Long, SubjectRef> subjectIndex) {
        return switch (sub.getSubType()) {
            case TOPIC -> topicMatch(sub.getSubKey(), content);
            case SUBJECT -> subjectMatch(sub.getSubKey(), content, subjectIndex);
            case EVENT_TYPE -> eventTypeMatch(sub.getSubKey(), content);
            case POLICY_THEME -> policyThemeMatch(sub.getSubKey(), content);
        };
    }

    /** 主题：标题或摘要含 subKey（大小写不敏感）；关键词为 subKey（文本必现）。 */
    private MatchHit topicMatch(String subKey, FeedContent content) {
        if (containsIgnoreCase(content.title(), subKey)
                || containsIgnoreCase(content.summary(), subKey)) {
            return new MatchHit("主题订阅:" + subKey, List.of(subKey));
        }
        return null;
    }

    /** 标的：公告/新闻按 subjectId 归属；政策按行业关联。subjectRef 缺失不命中。关键词为标的展示名。 */
    private MatchHit subjectMatch(
            String subKey, FeedContent content, Map<Long, SubjectRef> subjectIndex) {
        Long subjectId = parseSubjectId(subKey);
        if (subjectId == null) {
            return null;
        }
        SubjectRef ref = subjectIndex.get(subjectId);
        if (ref == null) {
            return null;
        }
        if (content.type() == FeedItemType.POLICY) {
            String industry = ref.industry();
            if (industry != null
                    && !industry.isBlank()
                    && content.relatedIndustries().contains(industry)) {
                return new MatchHit("标的订阅:" + nameOrCode(ref), List.of(nameOrCode(ref)));
            }
            return null;
        }
        if (subjectId.equals(content.subjectId())) {
            return new MatchHit("标的订阅:" + nameOrCode(ref), List.of(nameOrCode(ref)));
        }
        return null;
    }

    /** 事件类型：subKey 等于类型枚举名 / 含中文标签 / 公告分类含 subKey；按类型命中，无文本关键词。 */
    private MatchHit eventTypeMatch(String subKey, FeedContent content) {
        String key = subKey == null ? "" : subKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.equalsIgnoreCase(content.type().name())
                || containsIgnoreCase(key, typeLabel(content.type()))) {
            return new MatchHit("事件类型订阅:" + subKey, List.of());
        }
        if (content.type() == FeedItemType.ANNOUNCE
                && containsIgnoreCase(content.category(), key)) {
            return new MatchHit("事件类型订阅:" + subKey, List.of());
        }
        return null;
    }

    /** 政策主题：政策标题或摘要含 subKey；非政策不命中。关键词为 subKey（政策文本必现）。 */
    private MatchHit policyThemeMatch(String subKey, FeedContent content) {
        if (content.type() != FeedItemType.POLICY) {
            return null;
        }
        if (containsIgnoreCase(content.title(), subKey)
                || containsIgnoreCase(content.summary(), subKey)) {
            return new MatchHit("政策主题订阅:" + subKey, List.of(subKey));
        }
        return null;
    }

    /** 内容类型中文标签（事件类型匹配用）。 */
    private static String typeLabel(FeedItemType type) {
        return switch (type) {
            case ANNOUNCE -> "公告";
            case NEWS -> "新闻";
            case POLICY -> "政策";
            case RECOMMENDATION -> "推荐";
        };
    }

    /** 标的展示名（优先 name，缺失用 code）。 */
    private static String nameOrCode(SubjectRef ref) {
        return ref.name() != null && !ref.name().isBlank() ? ref.name() : ref.code();
    }

    /** 大小写不敏感 contains（haystack/null 安全）。 */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** 解析标的订阅 subKey 为 subjectId（Long）；非数字返回 null（跳过，不阻断）。 */
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

    /** 单次命中结果（原因串 + 可高亮关键词）。 */
    private record MatchHit(String reason, List<String> keywords) {}
}
