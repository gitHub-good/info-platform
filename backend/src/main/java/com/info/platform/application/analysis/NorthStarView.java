package com.info.platform.application.analysis;

import java.util.List;

/**
 * 北极星指标视图（M18 T158，REQ 拍板四，{@code GET /api/v1/north-star}）：六指标卡一端点聚合 + 7 天入库迷你趋势。
 *
 * <p><b>定位（拍板四-1）</b>：实时态驾驶舱仪表——区块数字<b>不是新口径</b>，是既有口径的聚合呈现：感知延迟沿大盘 incremental-only-v1、覆盖率沿 L1
 * 日分布（申万 31 命中比例）、采纳率沿 adopt-v1、稳定源沿「默认启用 + 周成功率 ≥95%」、成本沿 cost-v2（今日占比 + 单条两线）；验收快照（窗口首测）走 V2.0
 * 收口报告文档态，不走本端点。
 *
 * <p><b>状态语义（三态判定 + 可判定未达标）</b>：
 *
 * <ul>
 *   <li>{@code MET} 达标（恰等目标值算达标：P50 ≤5min、覆盖率 ≥90%、稳定源 ≥30、采纳率 ≥30%、占比 ≤60%、单条 ≤0.02 元）；
 *   <li>{@code NOT_MET} 可判定未达标（规模/成本类指标无样本也欠额，如实判定）；
 *   <li>{@code INSUFFICIENT} 样本不足 → 首跑校准条款（沿 M16 拍板五先例：零延迟样本/零归类行/曝光 &lt;30 张）。
 * </ul>
 *
 * @param basis 口径版本串（ns-v1；口径调整升版，不静默）
 * @param generatedAt 聚合时刻（ISO 文本）
 * @param latency 感知延迟卡（当日增量轮 P50/P90 与大盘端点同源）
 * @param coverage 行业覆盖率卡（当日 L1 归类分布，申万 31 命中比例，容器不计分子）
 * @param stableSources 稳定源卡（7 天窗成功率 ≥95% 的启用源数）
 * @param dailyIntake 日净入库卡（今日值 + 7 天日均值，去重后口径同大盘）
 * @param adoptRate 采纳率卡（当日 adopt-v1；曝光 &lt;30 张走校准条款）
 * @param costGuard 成本护栏两线卡（今日占比 ≤60% + 单条 ≤20000 微元；回灌日触线属设计内两级降级）
 * @param intakeTrend 7 天日净入库序列（升序，缺行日 0 填充，迷你趋势数据面）
 */
public record NorthStarView(
        String basis,
        String generatedAt,
        LatencyCard latency,
        CoverageCard coverage,
        StableSourcesCard stableSources,
        DailyIntakeCard dailyIntake,
        AdoptRateCard adoptRate,
        CostGuardCard costGuard,
        List<IntakePoint> intakeTrend) {

    /** 口径版本串（ns-v1，REQ 拍板四-2 六指标口径锁定）。 */
    public static final String BASIS = "ns-v1";

    /** 达标（恰等目标值含边界）。 */
    public static final String STATUS_MET = "MET";

    /** 可判定未达标（如实呈现，规模/成本类无样本也欠额）。 */
    public static final String STATUS_NOT_MET = "NOT_MET";

    /** 样本不足 → 首跑校准条款（版本化留档，不硬凑）。 */
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT";

    /** 感知延迟卡（与大盘 global.latency 同源同值；无样本 p50/p90 为 null）。 */
    public record LatencyCard(
            Long p50Millis, Long p90Millis, long sampleCount, String status, String basis) {}

    /** 行业覆盖率卡（hitIndustries/totalIndustries = 分子分母直出，对账可复算）。 */
    public record CoverageCard(
            Double coverageRatio,
            int hitIndustries,
            int totalIndustries,
            long classifiedToday,
            String status) {}

    /** 稳定源卡（enabledCount = 启用未删源数；windowDays 固定 7）。 */
    public record StableSourcesCard(
            int stableCount, int enabledCount, int windowDays, String status) {}

    /** 日净入库卡（todayNew 同大盘 todayNewCount；avg7d = 含当日 7 天窗日均）。 */
    public record DailyIntakeCard(long todayNew, double avg7d, String status) {}

    /** 采纳率卡（exposure = pushDelivered + viewExposed，adopt-v1 三口径直出）。 */
    public record AdoptRateCard(
            Double adoptRate, long exposure, long adopted, String status, String basis) {}

    /** 成本护栏两线卡（usageRatio = 今日成本/日预算；perItemMicros = 今日成本/今日净入库，零入库为 null）。 */
    public record CostGuardCard(
            Double usageRatio,
            long todayCostMicros,
            long budgetMicros,
            Double perItemMicros,
            String costBasis,
            String status) {}

    /** 7 天入库序列点（date = yyyy-MM-dd Asia/Shanghai）。 */
    public record IntakePoint(String date, long count) {}
}
