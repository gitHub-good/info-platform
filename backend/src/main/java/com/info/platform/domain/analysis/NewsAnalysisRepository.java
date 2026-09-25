package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 管道逐条状态仓储端口（{@code news_analysis}，M15 T120，ADR-0046 裁决 1）。
 *
 * <p>领域层纯净接口。写路径批量 {@code INSERT OR IGNORE}（UNIQUE(news_id) 幂等，批窗口重入收敛）；L1 落库为条件 UPDATE 状态机（{@code
 * WHERE l1_status IN ('PENDING','FAILED')}——已 DONE 行不重复归类，幂等红线）。 读路径跨 {@code news_item} join
 * 取标题/摘要/源名（独立表代价 = 一次 join，日窗行数量级毫秒级，ADR-0046 裁决 1 论证）。
 */
public interface NewsAnalysisRepository {

    /**
     * 批量幂等建行（INSERT OR IGNORE，UNIQUE(news_id) 兜底重入）。
     *
     * @return 实插行数（批窗口重复 tick / 竞态下小于入参数）
     */
    int insertIgnoreBatch(List<NewsAnalysis> rows);

    /**
     * 取「入库已过缓冲期仍未有 analysis 行」的 news_item（L0 建行候选，方案 §4.2：X=2min 缓冲避免与摄取事务竞态）。
     *
     * <p>按 {@code published_at, id} 升序（近重复主条判定要求时间序先行）；排除软删源条目。
     *
     * @param createdBeforeIso news_item.created_at 上界（含，ISO-8601 文本 = now−2min）
     * @param limit 单 tick 摄取上限（防御性）
     */
    List<NewsCandidate> findUnanalyzed(String createdBeforeIso, int limit);

    /**
     * 近重复比较池：24h 窗口内 {@code l0_result='PASS'} 的存量条目（标题 + 发布时间）。
     *
     * @param publishedSinceIso news_item.published_at 下界（含，ISO-8601 文本 = now−nearDupWindowHours）
     * @param limit 池上限（量级 ≤700/24h，防御性护栏）
     */
    List<NewsCandidate> findPassPoolSince(String publishedSinceIso, int limit);

    /**
     * L1 待处理查询（方案 §4.3 伪码）：PASS + PENDING/FAILED + attempts 未满 + 24h 补跑窗口。
     *
     * @param createdSinceIso news_analysis.created_at 下界（含，24h 补跑窗口）
     * @param maxAttempts 当日重试上限（l1_attempts < maxAttempts 才再进批）
     * @param limit 单次取数上限
     */
    List<ClassificationCandidate> findPendingForL1(
            String createdSinceIso, int maxAttempts, int limit);

    /**
     * L1 结果条件落库（幂等红线）：{@code WHERE news_id=? AND l1_status IN ('PENDING','FAILED')}。
     *
     * @return 受影响行数（0 = 已 DONE 或行不存在——调用方不视为错误）
     */
    int applyL1Result(L1Write write);

    /**
     * L1 失败记账：attempts+1 且置 FAILED（网络类失败本 tick 放弃，下 tick 24h 窗口自然重试）。
     *
     * @return 累计受影响行数（幂等：无条件更新，重入只会推进真实计数）
     */
    int markL1Failed(List<Long> newsIds);

    /** 当日 L0 三态计数（status 端点数据面；key = l0_result 枚举名，缺态不出现在结果中）。 */
    Map<String, Long> countL0ByResultSince(String createdSinceIso);

    /** 当日 L1 三态计数（同上，key = l1_status 枚举名）。 */
    Map<String, Long> countL1ByStatusSince(String createdSinceIso);

    /** L0/近重复候选条目（news_item 投影：标题/摘要/发布时间是两段判定的全部输入）。 */
    record NewsCandidate(
            long newsId,
            long sourceId,
            String title,
            String summary,
            Instant publishedAt,
            Instant createdAt) {}

    /** L1 批量归类候选（join news_item/info_source：渲染条目行与 T+30min 口径所需字段）。 */
    record ClassificationCandidate(
            long newsId,
            String title,
            String summary,
            String sourceName,
            Instant publishedAt,
            Instant fetchedAt) {}

    /** L1 结果落库参数（分类产物 + 条件 UPDATE 锚点）。 */
    record L1Write(
            long newsId,
            String mainCategory,
            String rawMain,
            String subIndustry,
            Double confidence,
            boolean lowConfidence,
            String matchedSubjects,
            String promptVersion,
            Instant classifiedAt) {}
}
