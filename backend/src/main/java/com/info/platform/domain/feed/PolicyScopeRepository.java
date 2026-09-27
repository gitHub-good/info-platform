package com.info.platform.domain.feed;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 政策类条目共用读口仓储端口（{@code policy-scope-v1}，V2.3-M23 T201，REQ-20260927-19 拍板二 / ADR-0062 裁决一）。
 *
 * <p>领域层纯净接口：news_item JOIN info_source JOIN news_analysis 之上的<b>查询口径</b>（非物化新表）， 政策页 / 信息流
 * POLICY（T203 切换）/ 详情分区（T202）三消费面同一 SQL 骨架——
 *
 * <pre>
 * 政策类条目 = ① 源类别 = 政策 的 PASS 条目 ∪ ② 任意源 L1=监管·政策（DONE）的 PASS 条目
 * </pre>
 *
 * <p>默认仅 PASS（noise/near_dup 不入政策视图）；软删源条目排除（join 语义同资讯库读模型）； sourceCode 显式选源时 <b>旁路 ①②</b>（宏观源
 * stats_release/em_macro_indicators 可显式选出数，ADR-0062 随批 4）。 口径对账可独立复算：政策页 结果 ⊆ 资讯库同条件筛选结果（Gate 2
 * 断言，测试资产）。
 */
public interface PolicyScopeRepository {

    /** 口径分页查询（ORDER BY published_at DESC, id DESC；limit/offset 由调用方校验）。 */
    List<PolicyScopeRow> findPage(PolicyScopeFilter filter);

    /** 同口径精确计数（与 {@link #findPage} 同一 WHERE 组装，页数据与计数同口径单点）。 */
    long count(PolicyScopeFilter filter);

    /** 按 news id 取单条（同口径谓词，无 days 窗；出 scope 或不存在返回 empty——政策详情 404 依据）。 */
    Optional<PolicyScopeRow> findById(long newsId);

    /**
     * 政策类口径筛选（null/空维度 = 不过滤，全 AND 组合）。
     *
     * @param days 时间窗（天；&le;0 取 7、上限 90，沿 M9 政策页口径由服务层 clamp 后传入）
     * @param industry 行业过滤：申万行业 main 或 sub 命中；{@code 监管·政策} 容器仅 main（REQ 拍板七 3）
     * @param sourceCode 显式选源（<b>旁路 ①②</b>——含宏观源可显式选出数）
     * @param keyword 标题/摘要 LIKE（转义在仓储层，M9/ADR-0035 先例）
     * @param subjectCode 标的直接回联过滤（matched_subjects JSON 引号定界 LIKE）——详情分区 ① 路
     * @param industries 行业关联集（main/sub IN 命中，详情分区 ② 路；与 subjectCode 为 OR 并集）
     * @param beforeId keyset 游标（上一页末条 news id：取排序位置严格早于该条的行；null = 首页）
     * @param limit 页大小（1~200）
     * @param offset 偏移（0 起）
     */
    record PolicyScopeFilter(
            int days,
            String industry,
            String sourceCode,
            String keyword,
            String subjectCode,
            Set<String> industries,
            Long beforeId,
            int limit,
            int offset) {

        /** 无附加过滤的最小查询（days 窗 + 分页）。 */
        public static PolicyScopeFilter ofWindow(int days, int limit, int offset) {
            return new PolicyScopeFilter(
                    days, null, null, null, null, Set.of(), null, limit, offset);
        }
    }

    /**
     * 政策类条目行（三表 join 投影：条目 + 源展示列 + L1 归类产物）。
     *
     * @param matchedSubjectsJson {@code news_analysis.matched_subjects} 原文（{@code
     *     [{code,name,industry}]}； null = 未回联——消费方自行解析，仓储不持有解析语义）
     */
    record PolicyScopeRow(
            long newsId,
            String title,
            String summary,
            String url,
            Instant publishedAt,
            String sourceCode,
            String sourceName,
            String sourceCategory,
            String mainCategory,
            String subIndustry,
            String matchedSubjectsJson) {}
}
