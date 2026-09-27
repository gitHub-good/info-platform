package com.info.platform.application.policy;

import com.info.platform.domain.feed.PolicyScopeRepository;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 政策类条目共用读口服务（{@code policy-scope-v1}，V2.3-M23 T201，方案 §3.1 / ADR-0062 裁决一）。
 *
 * <p>政策时事页（本类消费面）、详情分区（T202 {@code SubjectPolicySectionService}）、信息流 POLICY（T203 切换）
 * 三消费面共用的唯一查询入口：政策源 PASS ∪ 全源 L1=监管·政策（仅 PASS，软删源排除）——REQ 拍板二版本化口径， <b>非物化新表</b>。 口径串 {@link
 * #SCOPE_BASIS} 随响应 {@code basis} 字段返回（行业热度 basis 同款惯例）；未来口径升 v2 只改一处。
 *
 * <p>days clamp（M9 政策页口径沿用）：&le;0 取 7、上限 90。
 */
@Service
public class PolicyScopeQueryService {

    /** 版本化口径串（响应 basis 字段与对账锚，REQ 拍板二）。 */
    public static final String SCOPE_BASIS = "policy-scope-v1";

    /** days 缺省（M9 沿用）。 */
    static final int DEFAULT_DAYS = 7;

    /** days 上限（M9 沿用）。 */
    static final int MAX_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(PolicyScopeQueryService.class);

    private final PolicyScopeRepository repository;

    public PolicyScopeQueryService(PolicyScopeRepository repository) {
        this.repository = repository;
    }

    /**
     * 口径分页查询（页数据与计数同 WHERE 单点）。
     *
     * @param filter 业务过滤（days 在本入口 clamp；limit/offset 由调用方给出）
     */
    public PolicyScopePage list(PolicyScopeFilter filter) {
        PolicyScopeFilter effective = clamped(filter);
        long startedAt = System.currentTimeMillis();
        List<PolicyScopeRow> rows = repository.findPage(effective);
        long total = repository.count(effective);
        log.debug(
                "policy-scope 查询 days={} industry={} sourceCode={} keyword={} subjectCode={}"
                        + " industries={} total={} rows={} 耗时{}ms",
                effective.days(),
                effective.industry(),
                effective.sourceCode(),
                effective.keyword(),
                effective.subjectCode(),
                effective.industries() == null ? 0 : effective.industries().size(),
                total,
                rows.size(),
                System.currentTimeMillis() - startedAt);
        return new PolicyScopePage(rows, total);
    }

    /** 按 news id 取单条（同口径谓词，无 days 窗；出 scope 返回 empty——政策详情 404 依据）。 */
    public Optional<PolicyScopeRow> findByNewsId(long newsId) {
        return repository.findById(newsId);
    }

    /** days clamp（&le;0 → 7，&gt;90 → 90；M9 政策页窗口口径沿用）。 */
    static int clampDays(int days) {
        return days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
    }

    private static PolicyScopeFilter clamped(PolicyScopeFilter filter) {
        return new PolicyScopeFilter(
                clampDays(filter.days()),
                filter.industry(),
                filter.sourceCode(),
                filter.keyword(),
                filter.subjectCode(),
                filter.industries(),
                filter.beforeId(),
                filter.limit(),
                filter.offset());
    }

    /** 口径查询结果（页行 + 精确总数——total 与 rows 同一 WHERE）。 */
    public record PolicyScopePage(List<PolicyScopeRow> rows, long total) {}
}
