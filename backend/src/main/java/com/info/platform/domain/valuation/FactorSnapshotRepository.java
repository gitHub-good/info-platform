package com.info.platform.domain.valuation;

import java.util.List;
import java.util.Optional;

/**
 * 标的因子快照仓储端口（{@code subject_factor_snapshot} + 五路输入投影，M20 方案 §4.9）。领域层纯净接口： 写路径批
 * UPSERT（UNIQUE(subject_id, snapshot_date)——当日重跑同键覆盖）；读路径供快照编排（输入投影）、 coverage 对账（M20
 * 验收口径常驻）与详情评分（T171）。
 */
public interface FactorSnapshotRepository {

    /** 批量 UPSERT 快照行（500 行/批事务由实现保证）。 */
    int upsertAll(List<FactorSnapshotRow> rows);

    /** 活跃标的名录（A_SHARE status=1，id 升序）——计算全集与 coverage 对账分母。 */
    List<SubjectRef> findActiveSubjects();

    /** 活跃标的数（coverage 对账 activeSubjects）。 */
    long countActiveSubjects();

    /**
     * 事件窗投影：{@code event_item.event_date ∈ [fromDate, toDate]}（yyyy-MM-dd 含两端；窗界按 W2 宽取， W1
     * 剔除由域层防御双保险）——F1/F3/F4 原料 + 路 A 关联派生原料。
     */
    List<EventRef> findEventsInWindow(String fromDate, String toDate);

    /** 资讯回联窗投影：DONE 且 matched_subjects 非空的行（published_at ∈ [fromIso, toIso)）——路 B 关联派生原料。 */
    List<NewsLinkRow> findMatchedNewsInWindow(String fromIso, String toIso);

    /** H24 热度快照行（31 申万常驻，缺行由域层记 0）——F2 名次归一原料。 */
    List<HeatRow> findH24Heat();

    /** 最新快照日（yyyy-MM-dd；无任何快照返回 empty——Job 未跑过）。 */
    Optional<String> findLatestSnapshotDate();

    /** 指定快照日行数（coverage snapshotRows）。 */
    long countByDate(String snapshotDate);

    /** 指定快照日 total_score 严格大于 score 的行数（排名 = 该值 + 1，并列同名次）。 */
    long countScoreGreaterThan(String snapshotDate, double score);

    /** 指定快照日 data_flags 含该 flag 的行数（JSON 引号定界 LIKE，coverage flagCounts）。 */
    long countFlagged(String snapshotDate, String flag);

    /** 标的最新快照行（snapshot_date 降序首行；无快照返回 empty——30086 语义）。 */
    Optional<FactorSnapshotRow> findLatestBySubject(long subjectId);

    /** 活跃标的投影（计算全集元素）。 */
    record SubjectRef(long id, String code, String name) {}

    /** 事件窗投影（事件实体 + 回联代码 + 受影响行业）。 */
    record EventRef(
            ValuationEvent event, List<String> subjectCodes, List<String> affectedIndustries) {}

    /** 资讯回联投影（main/sub 行业 + 发布日 Shanghai 口径）。 */
    record NewsLinkRow(
            List<String> subjectCodes,
            String mainCategory,
            String subIndustry,
            java.time.LocalDate publishedDate) {}
}
