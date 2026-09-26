package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F1 事件催化强度（领域纯函数，主维，M20 方案 §4.2）：
 *
 * <pre>{@code
 * raw1 = Σ_{e∈E(s,W1), imp∈{HIGH,MEDIUM}} impCoef × dirCoef × typeCoef1 × decay(e)
 * F1   = 100 × raw1 / (raw1 + K1)        （饱和映射，K1 缺省 3.0）
 * }</pre>
 *
 * <p>impCoef 对齐 heat-v1（HIGH 1.0 / MEDIUM 0.5，LOW 不进窗）；dirCoef BULLISH 1.0 / NEUTRAL 0.3 / BEARISH
 * 0.0 （利空不进催化维，归 F4）；typeCoef1 九类系数冻结。窗口 W1 与半衰期锚定 snapshotDate。entries cap 10 按贡献降序。
 */
public final class CatalystFactor {

    /** 明细条目上限（§4.5 契约：cap 10，明细是缓存不是真相）。 */
    static final int ENTRY_CAP = 10;

    private CatalystFactor() {}

    /** F1 结果（score [0,100] + raw + 依据条目）。 */
    public record Result(double score, double raw, List<FactorEntry> entries) {}

    /** 单条事件系数（impCoef × dirCoef × typeCoef1 的合成乘积）。 */
    static double coefOf(ValuationEvent event) {
        return importanceCoef(event.importance())
                * directionCoef(event.direction())
                * typeCoef(event.eventType());
    }

    /** impCoef：HIGH 1.0 / MEDIUM 0.5（LOW 过滤在窗口判定，§4.2 imp ∈ {HIGH,MEDIUM}）。 */
    static double importanceCoef(Importance importance) {
        return importance == Importance.HIGH ? 1.0 : 0.5;
    }

    /** dirCoef：BULLISH 1.0 / NEUTRAL 0.3 / BEARISH 0.0（利空零贡献，进 F4）。 */
    static double directionCoef(Direction direction) {
        return switch (direction) {
            case BULLISH -> 1.0;
            case NEUTRAL -> 0.3;
            case BEARISH -> 0.0;
        };
    }

    /** typeCoef1 九类冻结系数（§4.2）。 */
    static double typeCoef(EventType type) {
        return switch (type) {
            case EARNINGS_FORECAST, MAJOR_CONTRACT -> 1.0;
            case BUYBACK_CHANGE, TECH_BREAKTHROUGH -> 0.9;
            case MA_MERGER -> 0.8;
            case POLICY_RELEASE -> 0.6;
            case EXEC_CHANGE -> 0.4;
            case REGULATORY_PENALTY -> 0.3;
            case OTHER -> 0.5;
        };
    }

    /**
     * 计算 F1（纯函数：输入窗内事件 + 参数；无时钟/随机/外部调用）。
     *
     * @param events 标的的窗内候选事件（越窗/未来日/LOW/BEARISH 由本函数剔除）
     * @param snapshotDate 快照口径日（age 与窗口锚点）
     */
    public static Result compute(
            List<ValuationEvent> events, LocalDate snapshotDate, ValuationParams params) {
        double raw = 0.0;
        List<FactorEntry> entries = new ArrayList<>();
        for (ValuationEvent event : events == null ? List.<ValuationEvent>of() : events) {
            long age =
                    FactorMath.ageInWindow(
                            event.eventDate(), snapshotDate, params.catalystWindowDays());
            if (age == FactorMath.OUT_OF_WINDOW || event.importance() == Importance.LOW) {
                continue;
            }
            double coef = coefOf(event);
            if (coef <= 0.0) {
                continue; // BEARISH（dirCoef 0）零贡献不入明细
            }
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
                Comparator.comparingDouble((FactorEntry entry) -> entry.coef() * entry.decay())
                        .reversed()
                        .thenComparingLong(FactorEntry::eventId));
        double score = 100.0 * raw / (raw + params.k1Saturation());
        return new Result(score, raw, cap(entries));
    }

    static List<FactorEntry> cap(List<FactorEntry> entries) {
        return List.copyOf(entries.subList(0, Math.min(ENTRY_CAP, entries.size())));
    }
}
