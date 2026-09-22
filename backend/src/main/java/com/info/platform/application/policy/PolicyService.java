package com.info.platform.application.policy;

import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 政策时事流应用服务（对齐 §4.1.5 + PRD 故事 4）。
 *
 * <p>{@code listPolicies}：调 {@link PolicyRepository#findRecent} 游标分页（newest-first，id
 * DESC，id&lt;cursor LIMIT 20）+ 行业过滤，返回政策列表（每条含标题/来源/时间/摘要/关联行业标签）+ nextCursor。
 *
 * <p>{@code getPolicy}：政策详情 + <b>关联自选标的</b>（按 relatedIndustries 匹配当前用户 watchlist 含该行业的标的： 取用户全部启用清单
 * → 展开清单项 subjectId → 调 {@link SubjectRepository#findById} 取 subject.industry → industry ∈
 * policy.relatedIndustries 即关联）。ai_tendency 返回 {@code 0} 未判（T28 AI 倾向判断填）。
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    /** 游标分页页大小（对齐 §4.4 游标分页 LIMIT 20）。 */
    private static final int PAGE_SIZE = 20;

    private final PolicyRepository policyRepository;
    private final WatchlistRepository watchlistRepository;
    private final SubjectRepository subjectRepository;

    public PolicyService(
            PolicyRepository policyRepository,
            WatchlistRepository watchlistRepository,
            SubjectRepository subjectRepository) {
        this.policyRepository = policyRepository;
        this.watchlistRepository = watchlistRepository;
        this.subjectRepository = subjectRepository;
    }

    /**
     * 政策列表（游标分页 + 行业过滤）。
     *
     * @param days 时间窗（天）
     * @param industry 行业过滤；null/blank 不过滤
     * @param cursor 游标（上一页末条 id）；null/0 表首页
     */
    public PolicyListView listPolicies(int days, String industry, Long cursor) {
        List<PolicyItem> items =
                policyRepository.findRecent(days, normalize(industry), cursor, PAGE_SIZE);
        List<PolicyView> views = items.stream().map(PolicyService::toView).toList();
        Long nextCursor = items.size() < PAGE_SIZE ? null : items.get(items.size() - 1).getId();
        return new PolicyListView(views, nextCursor);
    }

    /**
     * 政策详情 + 关联自选标的 + ai_tendency(0 未判)。
     *
     * @param id 政策条目 id
     * @param userId 当前用户（行级权限：只取该用户 watchlist）
     */
    public PolicyDetailView getPolicy(Long id, long userId) {
        PolicyItem item =
                policyRepository
                        .findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_NOT_FOUND));
        List<RelatedSubjectView> related = findRelatedSubjects(userId, item.getRelatedIndustries());
        log.debug("政策详情 id={} userId={} 关联标的 {} 条", id, userId, related.size());
        return new PolicyDetailView(
                item.getId(),
                item.getTitle(),
                item.getSource(),
                item.getPublishedAt() == null ? null : item.getPublishedAt().toString(),
                item.getSummary(),
                item.getRelatedIndustries(),
                item.getSourceUrl(),
                item.getAiTendency().code(),
                related);
    }

    /**
     * 关联自选标的：取当前用户全部启用清单 → 展开清单项 → 按 subject.industry ∈ policy.relatedIndustries 过滤。
     *
     * <p>无 relatedIndustries / 无 watchlist / 行业不匹配 → 空列表（不阻断，对齐 §4.1.5 详情含关联自选标的）。 subject.industry
     * 为 null（标的未录行业）或不在 policy.relatedIndustries → 不关联。
     */
    private List<RelatedSubjectView> findRelatedSubjects(
            long userId, List<String> relatedIndustries) {
        if (relatedIndustries == null || relatedIndustries.isEmpty()) {
            return List.of();
        }
        Set<String> industrySet = new HashSet<>(relatedIndustries);
        List<Watchlist> watchlists = watchlistRepository.findAllByOwnerId(userId);
        List<RelatedSubjectView> result = new ArrayList<>();
        for (Watchlist wl : watchlists) {
            for (WatchlistItem item : wl.getItems()) {
                Subject subject = subjectRepository.findById(item.getSubjectId()).orElse(null);
                if (subject == null) {
                    continue;
                }
                String industry = subject.getIndustry();
                if (industry != null && industrySet.contains(industry)) {
                    result.add(
                            new RelatedSubjectView(
                                    subject.getSubjectCode().value(), subject.getName(), industry));
                }
            }
        }
        return result;
    }

    private static PolicyView toView(PolicyItem item) {
        return new PolicyView(
                item.getId(),
                item.getTitle(),
                item.getSource(),
                item.getPublishedAt() == null ? null : item.getPublishedAt().toString(),
                item.getSummary(),
                item.getRelatedIndustries());
    }

    private static String normalize(String industry) {
        return industry == null ? null : industry.trim();
    }
}
