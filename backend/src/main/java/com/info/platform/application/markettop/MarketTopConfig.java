package com.info.platform.application.markettop;

/**
 * 全市场榜单配置载荷（{@code market.top} 键，M21 方案 §4.7.3）：粗筛池大小 / 深析候选数 / scene-10 成本护栏 / 行业成员覆盖率预检 阈值。读侧
 * {@link MarketTopConfigSettings} 字段级回退缺省；写侧 {@code MarketTopConfigValidator}（30091）把关。
 *
 * @param poolSize 粗筛池大小（缺省 300，可配 100~800——漏斗第二层 ≤10% 收敛）
 * @param deepDiveLimit 深析候选数（缺省 40，硬校验 30~50——蓝图区间，「全量 LLM 逐股永不发生」的配置面防线）
 * @param deepDiveCostCapRatio scene-10 子预算占管道日预算上限（缺省 0.30，0.05~1.0——触顶停剩余深析，T186 消费）
 * @param diveCostEstimateMicros 单次深析成本保守预估（μ¥，缺省 100000 ≈ ¥0.10/次，可配——预检口径，T186 消费）
 * @param memberCoverageFloor 行业成员覆盖率预检阈值（缺省 0.80，0~1——MARKET_TOP_JOB 阶段 0 回填触发线）
 */
public record MarketTopConfig(
        int poolSize,
        int deepDiveLimit,
        double deepDiveCostCapRatio,
        long diveCostEstimateMicros,
        double memberCoverageFloor) {

    /** 代码缺省（方案 §4.7.3 冻结值）。 */
    public static MarketTopConfig defaults() {
        return new MarketTopConfig(300, 40, 0.30, 100_000L, 0.80);
    }
}
