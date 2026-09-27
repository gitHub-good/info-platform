package com.info.platform.application.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.policy.PolicyScopeQueryService.PolicyScopePage;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 政策时事流应用服务（V2.3-M23 T201 数据面切换：policy_item → news_item 政策类条目，ADR-0062）。
 *
 * <p>读口 = {@link PolicyScopeQueryService}（policy-scope-v1：政策源 PASS ∪ 全源 L1=监管·政策，仅 PASS、软删源排除）；
 * <b>policy_item 零读取</b>（Gate 2 代码级断言：本类无 PolicyRepository 依赖——轨 B 双 Job 照常运行至 T203 退役）。
 *
 * <ul>
 *   <li>{@code listPolicies}：游标模式（keyset：cursor = 上一页末条 news id，取排序位置严格更早的行）；
 *   <li>{@code listPoliciesPaged}：页码模式（count + findPage 同一 filter，M9
 *       模式沿用；industry/keyword/sourceCode 组合过滤，越界页空列表 + 真实 total，ADR-0035）；
 *   <li>{@code getPolicy}：news 背书详情 + matchedSubjects 库内直读 + relatedEvents（event_item news_id 锚，
 *       UNIQUE 至多一条；direction 即 ai_tendency 退役后的倾向承接面）。
 * </ul>
 *
 * <p>sourceCode 显式选源旁路 scope ①②（宏观源可显式选出数，ADR-0062 随批 4）。
 */
@Service
public class PolicyService {

    /** 游标分页页大小（对齐 §4.4 游标分页 LIMIT 20）。 */
    private static final int PAGE_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyScopeQueryService policyScope;
    private final EventItemRepository eventItemRepository;
    private final ObjectMapper objectMapper;

    public PolicyService(
            PolicyScopeQueryService policyScope,
            EventItemRepository eventItemRepository,
            ObjectMapper objectMapper) {
        this.policyScope = policyScope;
        this.eventItemRepository = eventItemRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 政策列表（游标模式）：policy-scope 窗内按 published_at DESC, id DESC 取页，keyset 续取。
     *
     * @param days 时间窗（天；clamp 归读口）
     * @param industry 行业过滤（申万 main/sub 或 监管·政策 容器仅 main）；null/blank 不过滤
     * @param cursor 游标（上一页末条 news id）；null/0 表首页
     */
    public PolicyListView listPolicies(int days, String industry, Long cursor) {
        Long beforeId = cursor == null || cursor <= 0 ? null : cursor;
        PolicyScopePage page =
                policyScope.list(
                        new PolicyScopeFilter(
                                days,
                                normalize(industry),
                                null,
                                null,
                                null,
                                Set.of(),
                                beforeId,
                                PAGE_SIZE,
                                0));
        List<PolicyView> views = page.rows().stream().map(this::toView).toList();
        Long nextCursor =
                views.size() < PAGE_SIZE ? null : page.rows().get(page.rows().size() - 1).newsId();
        return new PolicyListView(views, nextCursor, PolicyScopeQueryService.SCOPE_BASIS);
    }

    /**
     * 政策列表（页码模式，M9 T60/T62 模式沿用）：count + findPage 同一 filter。
     *
     * @param industry L1 口径行业过滤（申万 31 行业 main 或 sub / 监管·政策 容器仅 main）
     * @param sourceCode 源筛选（显式选源旁路 scope ①②——宏观源可显式选出数）
     */
    public PolicyPagedView listPoliciesPaged(
            int days, String industry, String keyword, String sourceCode, int page, int size) {
        long startedAt = System.currentTimeMillis();
        PolicyScopeFilter filter =
                new PolicyScopeFilter(
                        days,
                        normalize(industry),
                        normalize(sourceCode),
                        keyword,
                        null,
                        Set.of(),
                        null,
                        size,
                        (int) Math.min((page - 1) * (long) size, Integer.MAX_VALUE));
        PolicyScopePage result = policyScope.list(filter);
        List<PolicyView> views = result.rows().stream().map(this::toView).toList();
        log.debug(
                "政策页码列表 days={} industry={} sourceCode={} keyword={} page={} size={}"
                        + " total={} 耗时{}ms",
                days,
                industry,
                sourceCode,
                keyword,
                page,
                size,
                result.total(),
                System.currentTimeMillis() - startedAt);
        return new PolicyPagedView(
                views, result.total(), page, size, PolicyScopeQueryService.SCOPE_BASIS);
    }

    /** 政策详情（id = news_item.id）：同口径谓词锚（出 scope → 30040）+ 回联标的 + 关联 L2 事件（倾向承接面）。 */
    public PolicyDetailView getPolicy(Long id) {
        PolicyScopeRow row =
                policyScope
                        .findByNewsId(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_NOT_FOUND));
        List<RelatedEventView> relatedEvents =
                eventItemRepository
                        .findByNewsId(id)
                        .map(PolicyService::toEventView)
                        .map(List::of)
                        .orElseGet(List::of);
        log.debug("政策详情 id={} 关联事件 {} 条", id, relatedEvents.size());
        return new PolicyDetailView(
                row.newsId(),
                row.title(),
                row.summary(),
                row.url(),
                row.sourceCode(),
                row.sourceName(),
                row.publishedAt() == null ? null : row.publishedAt().toString(),
                row.mainCategory(),
                row.subIndustry(),
                matchedSubjects(row),
                relatedEvents);
    }

    /** matched_subjects JSON → 视图（库内值直读；损坏容错空表——回联列非权威面，不阻断详情）。 */
    private List<MatchedSubjectView> matchedSubjects(PolicyScopeRow row) {
        String json = row.matchedSubjectsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<SubjectRef> refs =
                    objectMapper.readValue(
                            json,
                            objectMapper
                                    .getTypeFactory()
                                    .constructCollectionType(List.class, SubjectRef.class));
            return refs.stream()
                    .filter(ref -> ref.code() != null && !ref.code().isBlank())
                    .map(ref -> new MatchedSubjectView(ref.code(), ref.name(), ref.industry()))
                    .toList();
        } catch (Exception e) {
            log.warn("matched_subjects 解析失败 newsId={}: {}", row.newsId(), e.getMessage());
            return List.of();
        }
    }

    private PolicyView toView(PolicyScopeRow row) {
        return new PolicyView(
                row.newsId(),
                row.title(),
                row.summary(),
                row.url(),
                row.sourceCode(),
                row.sourceName(),
                row.publishedAt() == null ? null : row.publishedAt().toString(),
                row.mainCategory(),
                row.subIndustry(),
                matchedSubjects(row));
    }

    private static RelatedEventView toEventView(EventItem event) {
        return new RelatedEventView(
                event.getId(),
                event.getEventType().name(),
                event.getSummary(),
                event.getDirection().name(),
                event.getImportance().name(),
                event.getEventDate());
    }

    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** matched_subjects JSON 元素（[{code,name,industry}]——与 SubjectMatcher.MatchedSubject 同形）。 */
    private record SubjectRef(String code, String name, String industry) {}
}
