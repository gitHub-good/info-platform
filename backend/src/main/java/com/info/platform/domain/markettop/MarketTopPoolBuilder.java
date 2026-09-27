package com.info.platform.domain.markettop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 粗筛层纯函数（M21 T181，方案 §4.3 + ADR-0059 裁决 2）：从当日快照全量行构建粗筛池——先排除后排序切分，同输入同切分（可复现）。
 *
 * <ul>
 *   <li>排除规则：E1 ST/风险警示（名称含 "ST"（含 *ST），沿 M20 RiskFactor 名称规则）；E2 无信号（F1=0 ∧ F2=0——纯并列组 30.0/22.0
 *       残留）。被排除计数进 funnel_stats 留痕；快照行不动（排除只作用于池）。
 *   <li>四键排序：total_score DESC → f_catalyst DESC → last_event_date DESC（NULL 视最旧）→ subject_id
 *       ASC——纯列排序、无 JSON 解析、无时钟、无随机（4952 只并列 30.0 的破并列键，OBS-M20-3）。
 *   <li>切分：池 = 排除后前 poolSize（合格 &lt; poolSize 时全取，eligible 留痕如实）；深析候选 = 池前 deepDiveLimit。
 * </ul>
 *
 * <p>层数断言（§4.3.4「快照行 &gt; 池 ≥ 深析 ≥ 榜数，违反即中止」的需求锁定机制化）：build 内部校验结构不变量； {@link #assertFunnelLayers}
 * 四值全量断言供 Job 阶段 3（T183）落库前调用——「全量 LLM 逐股永不发生」的运行时防线。
 */
public final class MarketTopPoolBuilder {

    /** E1 判定子串（A 股命名规则为大写 ST——含 *ST；RiskFactor 同口径）。 */
    static final String ST_MARKER = "ST";

    private MarketTopPoolBuilder() {}

    /** 粗筛候选行（快照行纯列投影——四键 + 排除判据字段）。 */
    public record Candidate(
            long subjectId,
            String subjectCode,
            String subjectName,
            double totalScore,
            double fCatalyst,
            double fConduction,
            String lastEventDate) {} // yyyy-MM-dd 文本（快照列原值；NULL = 无事件视最旧）

    /** 粗筛配置（值域校验归 MarketTopConfigValidator 30091；此处只防御非正数）。 */
    public record PoolConfig(int poolSize, int deepDiveLimit) {}

    /** 排除计数留痕（funnel_stats.excluded）。 */
    public record Exclusions(int st, int noSignal) {}

    /** 漏斗计数留痕（funnel_stats；poolSize/divePlanned 为实际值——合格不足时如实小于配置）。 */
    public record FunnelStats(
            long snapshotRows, int eligible, Exclusions excluded, int poolSize, int divePlanned) {}

    /** 粗筛结果：池 + 深析候选（池前缀切片）+ 漏斗留痕。 */
    public record PoolResult(
            List<Candidate> pool, List<Candidate> diveCandidates, FunnelStats funnel) {}

    /**
     * 构建粗筛池（确定性纯函数——同输入同输出，验收用例：两连跑池 id 序列相等）。
     *
     * @param candidates 当日快照全量行（顺序无关——排序键内含 subject_id 全序破并列）
     * @param config 池配置（poolSize ≥ 1、deepDiveLimit ≥ 0）
     * @throws IllegalArgumentException 非法配置（poolSize &lt; 1 / deepDiveLimit &lt; 0）
     */
    public static PoolResult build(List<Candidate> candidates, PoolConfig config) {
        if (config.poolSize() < 1) {
            throw new IllegalArgumentException("poolSize 须 ≥ 1: " + config.poolSize());
        }
        if (config.deepDiveLimit() < 0) {
            throw new IllegalArgumentException("deepDiveLimit 须 ≥ 0: " + config.deepDiveLimit());
        }
        List<Candidate> safe = candidates == null ? List.<Candidate>of() : candidates;
        int stExcluded = 0;
        int noSignalExcluded = 0;
        List<Candidate> eligible = new ArrayList<>(safe.size());
        for (Candidate candidate : safe) {
            if (isSt(candidate)) {
                stExcluded++;
                continue;
            }
            if (isNoSignal(candidate)) {
                noSignalExcluded++;
                continue;
            }
            eligible.add(candidate);
        }
        eligible.sort(FOUR_KEY_ORDER);

        List<Candidate> pool =
                List.copyOf(eligible.subList(0, Math.min(config.poolSize(), eligible.size())));
        List<Candidate> dive =
                List.copyOf(pool.subList(0, Math.min(config.deepDiveLimit(), pool.size())));

        // 结构不变量（build 层常驻断言，非严格链）：池 ⊆ 快照行、深析候选 ⊆ 池——合格 &lt; poolSize 时池 = 合格数
        // 可等于快照行数（§4.3.2「全取如实」）；「快照行 &gt; 池」严格链是生产形态守卫，归 Job 阶段 3 的
        // assertFunnelLayers（§4.3.4——池吞全量即全量 LLM 逐股形态，属落库前中止面）。
        if (pool.size() > safe.size() || dive.size() > pool.size()) {
            throw new IllegalStateException(
                    "粗筛结构不变量违反: snapshotRows="
                            + safe.size()
                            + ", pool="
                            + pool.size()
                            + ", divePlanned="
                            + dive.size());
        }
        return new PoolResult(
                pool,
                dive,
                new FunnelStats(
                        safe.size(),
                        eligible.size(),
                        new Exclusions(stExcluded, noSignalExcluded),
                        pool.size(),
                        dive.size()));
    }

    /**
     * 漏斗层数四值断言（§4.3.4，Job 阶段 3 落库前调用——「全量 LLM 逐股永不发生」的运行时防线）。
     *
     * @param snapshotRows 快照全量行数
     * @param poolCount 粗筛池实际行数
     * @param divePlanned 深析候选实际行数
     * @param topSize 榜单行数（恰 10 或如实不足）
     * @throws IllegalStateException 任一关系违反（宁缺毋错——JobRunStats 记 ERROR 并中止落库）
     */
    public static void assertFunnelLayers(
            long snapshotRows, int poolCount, int divePlanned, int topSize) {
        if (snapshotRows <= poolCount) {
            throw violation("快照行数须严格大于池行数", snapshotRows, poolCount, divePlanned, topSize);
        }
        if (poolCount < divePlanned) {
            throw violation("池行数须 ≥ 深析候选数", snapshotRows, poolCount, divePlanned, topSize);
        }
        if (divePlanned < topSize || topSize < 0) {
            throw violation("深析候选数须 ≥ 榜单行数 ≥ 0", snapshotRows, poolCount, divePlanned, topSize);
        }
    }

    private static IllegalStateException violation(
            String message, long snapshotRows, int poolCount, int divePlanned, int topSize) {
        return new IllegalStateException(
                "漏斗层数断言违反: "
                        + message
                        + "（snapshotRows="
                        + snapshotRows
                        + ", pool="
                        + poolCount
                        + ", divePlanned="
                        + divePlanned
                        + ", topSize="
                        + topSize
                        + "）——装配/配置错误，中止落库");
    }

    /** E1 ST/风险警示（名称含 "ST"，含 *ST——M20 RiskFactor 同口径大小写敏感）。 */
    static boolean isSt(Candidate candidate) {
        return candidate.subjectName() != null && candidate.subjectName().contains(ST_MARKER);
    }

    /** E2 无信号（F1=0 ∧ F2=0——纯并列组：0.4×0+0.2×0+0.2×50+0.2×100=30.0 残留）。 */
    static boolean isNoSignal(Candidate candidate) {
        return candidate.fCatalyst() == 0.0 && candidate.fConduction() == 0.0;
    }

    /**
     * 四键全序（可复现切分的唯一排序）：total DESC → f_catalyst DESC → last_event_date DESC（NULL 最后）→ subject_id
     * ASC。
     *
     * <p>NULL 最旧实现：{@code nullsFirst(naturalOrder())} 再整体 reversed——null 排在降序序列末尾（nullsLast +
     * reversed 会把 null 翻到最前，两写法不可混）。
     */
    static final Comparator<Candidate> FOUR_KEY_ORDER =
            Comparator.comparingDouble(Candidate::totalScore)
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(Candidate::fCatalyst).reversed())
                    .thenComparing(
                            Comparator.comparing(
                                            Candidate::lastEventDate,
                                            Comparator.nullsFirst(Comparator.naturalOrder()))
                                    .reversed())
                    .thenComparingLong(Candidate::subjectId);
}
