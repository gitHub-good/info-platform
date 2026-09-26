package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F3 基本面边际（领域纯函数，事件近似口径、带符号，M20 方案 §4.2）：
 *
 * <pre>{@code
 * raw3 = Σ_{e∈E(s,W1), type∈基本面类} sign3(e) × typeCoef3(e) × impCoef(e) × decay(e)
 * F3   = 50 + 50 × tanh(raw3 / K3)      （无信号 = 50 中性；±1.5 加权单位 → 88/12）
 * }</pre>
 *
 * <p>sign3：BULLISH +1 / NEUTRAL +0.2 / BEARISH −1；typeCoef3 五类（业绩/回购/合同/并购/技术）。业绩类事件同时计入 F1 与 F3
 * 是设计内重叠（蓝图裁决 1：F1 度量关注度动量、F3 度量基本面方向）。
 */
public final class FundamentalFactor {

    private FundamentalFactor() {}

    /** F3 结果（score [0,100] + raw + 依据条目按 |贡献| 降序）。 */
    public record Result(double score, double raw, List<FactorEntry> entries) {}

    /** sign3 带符号方向系数。 */
    static double signOf(Direction direction) {
        return switch (direction) {
            case BULLISH -> 1.0;
            case NEUTRAL -> 0.2;
            case BEARISH -> -1.0;
        };
    }

    /** typeCoef3 五类冻结系数（非基本面类返回 0 = 不进维）。 */
    static double typeCoef(EventType type) {
        return switch (type) {
            case EARNINGS_FORECAST -> 1.0;
            case BUYBACK_CHANGE -> 0.8;
            case MAJOR_CONTRACT -> 0.7;
            case MA_MERGER -> 0.5;
            case TECH_BREAKTHROUGH -> 0.4;
            default -> 0.0;
        };
    }

    /** 计算 F3（纯函数；窗口 W1 与半衰期锚定 snapshotDate）。 */
    public static Result compute(
            List<ValuationEvent> events, LocalDate snapshotDate, ValuationParams params) {
        double raw = 0.0;
        List<FactorEntry> entries = new ArrayList<>();
        for (ValuationEvent event : events == null ? List.<ValuationEvent>of() : events) {
            long age =
                    FactorMath.ageInWindow(
                            event.eventDate(), snapshotDate, params.catalystWindowDays());
            double typeCoef = typeCoef(event.eventType());
            if (age == FactorMath.OUT_OF_WINDOW || typeCoef <= 0.0) {
                continue;
            }
            double coef = signOf(event.direction()) * typeCoef * event.importance().coefficient();
            double decay = FactorMath.decay(age, params.halfLifeDays());
            raw += coef * decay;
            entries.add(
                    new FactorEntry(
                            event.eventId(),
                            event.summary(),
                            event.eventDate(),
                            event.direction(),
                            event.importance(),
                            coef,
                            decay));
        }
        entries.sort(
                Comparator.comparingDouble(
                                (FactorEntry entry) -> Math.abs(entry.coef() * entry.decay()))
                        .reversed()
                        .thenComparingLong(FactorEntry::eventId));
        double score = 50.0 + 50.0 * Math.tanh(raw / params.k3Saturation());
        return new Result(score, raw, cap(entries));
    }

    private static List<FactorEntry> cap(List<FactorEntry> entries) {
        return List.copyOf(entries.subList(0, Math.min(CatalystFactor.ENTRY_CAP, entries.size())));
    }
}
