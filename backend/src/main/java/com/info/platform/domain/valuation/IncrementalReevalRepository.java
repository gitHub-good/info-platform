package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Importance;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 增量重评留痕仓储端口（{@code incremental_reeval_log} + 扫描投影，M22 T190，V33 表 / ADR-0061 裁决 1/2）。领域层纯净接口： 消费幂等键
 * = UNIQUE(event_id)（LEFT JOIN 判重 + INSERT OR IGNORE 落库缓冲，RECOMMENDATION_FEED 同款扫描模式）；状态机 SCANNED →
 * RECOMPUTED → {NO_LINK | DEFERRED → LINKED | LINKED}，FAILED 旁路——一轮一事件一行，审计真相源与北极星时效 SQL
 * 数据面（event_created_at → top_version_at）。
 */
public interface IncrementalReevalRepository {

    /** 状态机常量（status 列值域，§4.3.1）。 */
    String STATUS_SCANNED = "SCANNED";

    String STATUS_RECOMPUTED = "RECOMPUTED";

    String STATUS_NO_LINK = "NO_LINK";

    String STATUS_LINKED = "LINKED";

    String STATUS_DEFERRED = "DEFERRED";

    String STATUS_FAILED = "FAILED";

    /**
     * 扫未消费事件：LEFT JOIN 留痕表判重 + {@code created_at ∈ [createdSince, createdBefore]} 窗（20s 落库缓冲上界 +
     * 24h 补跑窗下界）+ 重要度 ≥ minImportance（枚举序比较：HIGH &gt; MEDIUM &gt; LOW）。
     */
    List<ReevalEvent> findUnconsumedEvents(
            Importance minImportance, Instant createdBefore, Instant createdSince, int limit);

    /** 落 SCANNED 行（INSERT OR IGNORE——UNIQUE(event_id) 幂等，返回 0 = 已消费直跳）。 */
    int insertScanned(ReevalEvent event, String nowIso);

    /** 重算完成：前后分 + 重算时刻 + 判定结果落库，状态置 RECOMPUTED（snapshot_at 空 = 挂起轮重判不覆盖原值）。 */
    int markRecomputed(
            Collection<Long> eventIds,
            String subjectsJson,
            String snapshotAtIso,
            boolean judgePassed,
            String nowIso);

    /** 无转移语义的状态变更（NO_LINK / DEFERRED）。 */
    int markStatus(Collection<Long> eventIds, String status, String nowIso);

    /** 联动收口：版本号 + 版本时刻落库，状态置 LINKED（终态）。 */
    int markLinked(
            Collection<Long> eventIds, int topVersion, String topVersionAtIso, String nowIso);

    /** FAILED 旁路：异常摘要留痕（次日全量自然修复，对账 SQL 可查）。 */
    int markFailed(Collection<Long> eventIds, String errorMessage, String nowIso);

    /** 挂起轮（judge_passed=1 且 status ∈ {RECOMPUTED, DEFERRED}）——下轮 tick 重走联动段。 */
    List<PendingLink> findPendingLink();

    /** 同日最近 EVENT 版本落库时刻（联动防抖间隔基准；无 EVENT 版本返回 empty）。 */
    Optional<String> findLastEventVersionAt(String rankDate);

    /** 指定快照日的受影响标的当日行（前分读取——id 升序确定性）。 */
    List<SubjectScore> findScoresBySubjectIds(String snapshotDate, Collection<Long> subjectIds);

    /** 扫描事件投影（subjects 代码 + 受影响行业已解析）。 */
    record ReevalEvent(
            long eventId,
            String eventCreatedAtIso,
            String summary,
            Importance importance,
            List<String> subjectCodes,
            List<String> affectedIndustries) {}

    /** 挂起轮（subjects_json 携带重走联动段的受影响标的集）。 */
    record PendingLink(long eventId, String subjectsJson) {}

    /** 当日行标的分（前分留痕读取面）。 */
    record SubjectScore(long subjectId, String subjectCode, double totalScore) {}
}
