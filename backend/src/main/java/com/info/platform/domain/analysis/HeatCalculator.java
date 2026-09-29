package com.info.platform.domain.analysis;

import com.info.platform.domain.aggregation.Market;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业热度计算器（领域纯函数，M15 T123，方案 §3.6/§4.5 裁决 6）：条目计数 × 事件加权 × 指数时间衰减——K1 缺省 10（REQ 建议值 3 的算术勘定）、
 * impCoef 1.0/0.5/0.25、双窗半衰期 12h|48h；容器条目不进榜但事件扩散计入。
 *
 * <p>公式：{@code score(i,w) = Σ_main=PASS 条目 (1 + K1×impCoef[其事件]) × 0.5^(ageHours/hl(w)) +
 * Σ_事件(affected ∋ i 且条目 main ≠ i) K1×impCoef × 0.5^(ageHours/hl)}。事件条目自身行业走第一项
 * （1+K1×coef），其余受影响行业走第二项（K1×coef）——「降准」main=宏观不进榜、事件扩散到银行/房地产拿 K1×coef。
 */
public final class HeatCalculator {

    private HeatCalculator() {}

    /** 热度参数（{@code pipeline.heat} 键热改；basis 串随参数升版）。 */
    public record HeatParams(
            double k1,
            double impHigh,
            double impMedium,
            double impLow,
            double halfLifeHours24,
            double halfLifeHours7) {

        /** 代码缺省（方案 §3.6 冻结值）。 */
        public static HeatParams defaults() {
            return new HeatParams(10.0, 1.0, 0.5, 0.25, 12.0, 48.0);
        }

        /** 指定窗口的半衰期（小时）。 */
        public double halfLifeOf(HeatWindow window) {
            return window == HeatWindow.H24 ? halfLifeHours24 : halfLifeHours7;
        }

        /** 重要度系数（无事件 0）。 */
        public double impCoef(Importance importance) {
            if (importance == null) {
                return 0.0;
            }
            return switch (importance) {
                case HIGH -> impHigh;
                case MEDIUM -> impMedium;
                case LOW -> impLow;
            };
        }
    }

    /** 窗口内单条已归类条目（PASS+DONE 投影；事件字段可空 = 无事件）。 */
    public record HeatItem(
            String mainCategory,
            Instant publishedAt,
            Importance eventImportance,
            List<String> affectedIndustries) {}

    /** 单行业聚合值（零行业也常驻——0 分沉底）。 */
    public record IndustryHeat(double score, long newsCount, long eventCount) {

        static final IndustryHeat EMPTY = new IndustryHeat(0.0, 0, 0);
    }

    /**
     * 现算一个窗口的行业聚合（窗口外条目防御性跳过；返回按 {@link IndustryCategory#SW_INDUSTRIES} 全量零填， 容器不出现）。
     *
     * @param items 窗口候选条目（调用方已按 published_at 过滤，此处双保险）
     * @param windowEnd 窗口终点（ageHours 基准）
     * @param windowLength 窗口长度（prev 对齐与防御过滤共用）
     */
    public static Map<String, IndustryHeat> compute(
            List<HeatItem> items, HeatParams params, Instant windowEnd, Duration windowLength) {
        return compute(items, params, windowEnd, windowLength, Market.A_SHARE);
    }

    /**
     * 现算一个窗口的指定市场行业聚合（M29 T255，方案 §4 C11——按 (l1_market, main_category) 三市场各扫各自枚举，窗口逻辑零变化）：
     * 全量零填与进榜校验均按 {@code market} 枚举集（A 股申万 31 / 港股 31 / 美股 40——容器与 UNKNOWN 不出现，跨市场重名由 market 消歧）。
     *
     * @param market 市场口径（条目由调用方按 l1_market 过滤，此处白名单双保险）
     */
    public static Map<String, IndustryHeat> compute(
            List<HeatItem> items,
            HeatParams params,
            Instant windowEnd,
            Duration windowLength,
            Market market) {
        Map<String, IndustryHeat> acc = new LinkedHashMap<>();
        for (String industry : boardIndustriesOf(market)) {
            acc.put(industry, IndustryHeat.EMPTY);
        }
        Instant windowStart = windowEnd.minus(windowLength);
        for (HeatItem item : items) {
            // 防御双保险（取数 SQL 已按 [start, end) 过滤——此处双端含，不丢调用方已纳入的边界行）
            if (item.publishedAt().isBefore(windowStart) || item.publishedAt().isAfter(windowEnd)) {
                continue;
            }
            double decay = decayFactor(item.publishedAt(), windowEnd, params, windowLength);
            accumulateMain(acc, item, decay, params, market);
            accumulateEventSpread(acc, item, decay, params, market);
        }
        return Map.copyOf(acc);
    }

    /** 市场进榜枚举集（零填范围 = A 股申万 31 / 港股 31 / 美股 40；与 {@link IndustryCategory#isBoardIndustry} 同源）。 */
    private static java.util.Set<String> boardIndustriesOf(Market market) {
        if (market == Market.HK) {
            return IndustryCategory.HK_INDUSTRIES;
        }
        if (market == Market.US) {
            return IndustryCategory.US_INDUSTRIES;
        }
        return IndustryCategory.SW_INDUSTRIES;
    }

    private static void accumulateMain(
            Map<String, IndustryHeat> acc,
            HeatItem item,
            double decay,
            HeatParams params,
            Market market) {
        if (!IndustryCategory.isBoardIndustry(market, item.mainCategory())) {
            return; // 容器/他市场枚举条目不进榜（事件扩散除外）
        }
        double contribution = (1.0 + params.k1() * params.impCoef(item.eventImportance())) * decay;
        merge(acc, item.mainCategory(), contribution, 1, 0);
    }

    private static void accumulateEventSpread(
            Map<String, IndustryHeat> acc,
            HeatItem item,
            double decay,
            HeatParams params,
            Market market) {
        if (item.eventImportance() == null || item.affectedIndustries() == null) {
            return;
        }
        double k1Coef = params.k1() * params.impCoef(item.eventImportance());
        for (String industry : item.affectedIndustries()) {
            if (!IndustryCategory.isBoardIndustry(market, industry)) {
                continue; // 越界元素已在 L2 落库前丢弃，此处防御性双保险
            }
            int eventCount = 1; // 影响本行业的事件数（含 main == 本行业的直接命中）
            if (industry.equals(item.mainCategory())) {
                merge(acc, industry, 0.0, 0, eventCount); // 权重已在第一项（不双计）
                continue;
            }
            merge(acc, industry, k1Coef * decay, 0, eventCount);
        }
    }

    private static void merge(
            Map<String, IndustryHeat> acc,
            String industry,
            double scoreDelta,
            long newsDelta,
            long eventDelta) {
        IndustryHeat current = acc.getOrDefault(industry, IndustryHeat.EMPTY);
        acc.put(
                industry,
                new IndustryHeat(
                        current.score() + scoreDelta,
                        current.newsCount() + newsDelta,
                        current.eventCount() + eventDelta));
    }

    /** 衰减因子：0.5^(ageHours/halfLife)（窗口半衰期按 H24/D7 双轨）。 */
    static double decayFactor(
            Instant publishedAt, Instant windowEnd, HeatParams params, Duration windowLength) {
        HeatWindow window = windowLength.toHours() <= 24 ? HeatWindow.H24 : HeatWindow.D7;
        double ageHours = Duration.between(publishedAt, windowEnd).toMillis() / 3_600_000.0;
        double halfLife = params.halfLifeOf(window);
        return Math.pow(0.5, Math.max(0.0, ageHours) / halfLife);
    }

    /** 环比：prev=0 且 score&gt;0 记 100.0，双 0 记 0（表注释口径；prev&gt;0 常规公式）。 */
    public static double deltaPct(double score, double prev) {
        return IndustryHeatSnapshot.deltaPctOf(score, prev);
    }

    /** 口径版本串（参数热改后首快照起换串，榜单脚注直读）：{@code heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h}。 */
    public static String basis(HeatParams params) {
        return "heat-v1:k1="
                + compact(params.k1())
                + ";imp="
                + params.impHigh()
                + "/"
                + params.impMedium()
                + "/"
                + params.impLow()
                + ";hl="
                + compact(params.halfLifeHours24())
                + "h|"
                + compact(params.halfLifeHours7())
                + "h";
    }

    private static String compact(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
