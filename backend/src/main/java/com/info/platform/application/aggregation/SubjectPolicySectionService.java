package com.info.platform.application.aggregation;

import com.info.platform.application.aggregation.PolicySectionView.Fallback;
import com.info.platform.application.policy.PolicyScopeQueryService;
import com.info.platform.application.policy.PolicyScopeQueryService.PolicyScopePage;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 详情页政策分区服务（V2.3-M23 T202，方案 §3.3 / REQ 拍板三）：替换 {@code INDUSTRY_KEYWORDS} 3 行业热词字典 （新池 5221 标的永不命中
 * =「抓取不到」根因）——分区条目 = policy-scope-v1 近期条目中 ① matched_subjects 含该标的 （matchType=SUBJECT）∪ ② main/sub
 * ∈ 行业关联集（matchType=INDUSTRY，{@link SubjectIndustryAssociationReader} 与 F2/M22 同源）；①② 并集为空 →
 * 宏观兜底段（L1=监管·政策 近期条目 + 口径明示文案）。
 *
 * <p><b>MISSING 构造性消除</b>：①∪② 与兜底段同库同查询面（仅少标的过滤）二选其一必达——全量标的分区恒有内容（REQ 故事 3 场景 4）。条目双命中时 ①
 * 优先（徽章唯一，可解释）。库内同步毫秒级调用（替换原 future 外呼，首屏只降不升）。
 */
@Service
public class SubjectPolicySectionService {

    /** 关联段与兜底段同容量（沿 M12 分区容量惯例，方案 §4.3）。 */
    public static final int POLICY_SECTION_CAP = 10;

    /** 分区取数窗（天）：与关联窗 W2 同宽（缺省 30）——行业关联集由 30 天窗原料派生，分区条目同窗取数口径自洽； 实现裁量（方案「近期」未冻结粒度），窗口参数化随关联窗热调。 */
    public static final int SECTION_WINDOW_DAYS = 30;

    /** 兜底段口径明示文案（REQ 拍板三：不冒充关联）。 */
    public static final String FALLBACK_NOTE = "暂无与该标的行业直接相关的政策，以下为近期宏观政策";

    private static final Logger log = LoggerFactory.getLogger(SubjectPolicySectionService.class);

    /** 三来源分级（matchType 值域，REQ 拍板三呈现分级）。 */
    public static final String MATCH_TYPE_SUBJECT = "SUBJECT";

    public static final String MATCH_TYPE_INDUSTRY = "INDUSTRY";

    private final PolicyScopeQueryService policyScope;
    private final SubjectIndustryAssociationReader associationReader;

    public SubjectPolicySectionService(
            PolicyScopeQueryService policyScope,
            SubjectIndustryAssociationReader associationReader) {
        this.policyScope = policyScope;
        this.associationReader = associationReader;
    }

    /**
     * 单标的政策分区（关联命中段或宏观兜底段二选其一）。
     *
     * @param subjectCode 标的代码（SH600519 口径）
     */
    public PolicySectionView sectionOf(String subjectCode) {
        List<Association> associations = associationReader.associationsOf(subjectCode);
        Set<String> industries =
                associations.stream().map(Association::industry).collect(Collectors.toSet());

        PolicyScopePage union =
                policyScope.list(
                        new PolicyScopeFilter(
                                SECTION_WINDOW_DAYS,
                                null,
                                null,
                                null,
                                subjectCode,
                                industries,
                                null,
                                POLICY_SECTION_CAP,
                                0));
        List<PolicySectionView.Item> items =
                union.rows().stream().map(row -> itemOf(row, subjectCode)).toList();
        if (!items.isEmpty()) {
            log.debug(
                    "政策分区关联命中 subjectCode={} associations={} items={}",
                    subjectCode,
                    industries,
                    items.size());
            return new PolicySectionView(items, null, PolicyScopeQueryService.SCOPE_BASIS);
        }

        // 宏观兜底段：L1=监管·政策 近期条目（同容量；容器仅 main 命中）+ 口径明示文案
        PolicyScopePage fallbackPage =
                policyScope.list(
                        new PolicyScopeFilter(
                                SECTION_WINDOW_DAYS,
                                "监管·政策",
                                null,
                                null,
                                null,
                                Set.of(),
                                null,
                                POLICY_SECTION_CAP,
                                0));
        List<PolicySectionView.Item> fallbackItems =
                fallbackPage.rows().stream().map(row -> itemOf(row, null)).toList();
        return new PolicySectionView(
                List.of(),
                new Fallback(fallbackItems, FALLBACK_NOTE),
                PolicyScopeQueryService.SCOPE_BASIS);
    }

    /**
     * 条目分级：union 查询已按 ①∪② 过滤，此处逐条定徽章——回联命中 → SUBJECT（双命中时 ① 优先，徽章唯一可解释）； 仅行业命中 →
     * INDUSTRY；兜底段（subjectCode=null）无 matchType。
     */
    private static PolicySectionView.Item itemOf(PolicyScopeRow row, String subjectCode) {
        String matchType = null;
        if (subjectCode != null) {
            boolean subjectHit =
                    row.matchedSubjectsJson() != null
                            && row.matchedSubjectsJson().contains("\":\"" + subjectCode + "\"");
            matchType = subjectHit ? MATCH_TYPE_SUBJECT : MATCH_TYPE_INDUSTRY;
        }
        return new PolicySectionView.Item(
                row.newsId(),
                row.title(),
                row.url(),
                row.publishedAt() == null ? null : row.publishedAt().toString(),
                row.sourceName(),
                matchType);
    }
}
