package com.info.platform.domain.newspulse;

import java.util.List;
import java.util.Optional;

/**
 * 资讯脉搏仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>读取面：窗口条目装载（news_item ∩ news_analysis L0=PASS，重要性降序截断）+ 最新分析快照取回； 写入面：版本化追加（不做 UPSERT——快照即审计）。
 */
public interface NewsPulseRepository {

    /** 窗口内资讯条目（L0=PASS；重要性分降序、同分时间降序，截断 cap 条）。 */
    List<WindowItem> findWindowItems(String startIso, String endIso, int cap);

    /** 窗口内 PASS 条目总数（不截断——news_count 口径与列表 cap 解耦）。 */
    long countWindowItems(String startIso, String endIso);

    /** 窗口内 L1 已分类（DONE）条目数——classified_count 全窗口口径（与截断列表无关）。 */
    long countWindowClassified(String startIso, String endIso);

    /** 窗口条目投影（渲染 LLM 上下文与规则统计的最小字段集）。 */
    record WindowItem(
            long newsId,
            String title,
            String mainCategory,
            Double confidence,
            double importanceScore,
            String matchedSubjectsJson) {}

    /** 最新分析快照行（无 → empty）。 */
    Optional<PulseRow> findLatest(String windowKey);

    /** 各窗口最新快照（从未分析的窗口缺席——前端以 null 呈现「尚未分析」）。 */
    List<PulseRow> findLatestEachWindow();

    /** 追加分析快照（INSERT，id 回填）。 */
    PulseRow insert(PulseRow row);

    /**
     * 分析快照行（表 news_pulse_analysis 全列投影）。
     *
     * @param id 缺省 null（插入前）
     */
    record PulseRow(
            Long id,
            String windowKey,
            String windowStart,
            String windowEnd,
            int newsCount,
            int classifiedCount,
            String industryStats,
            String marketStats,
            String analysis,
            String model,
            String promptVersion,
            String triggerSource,
            boolean degraded,
            String degradedReason,
            String createdAt) {}
}
