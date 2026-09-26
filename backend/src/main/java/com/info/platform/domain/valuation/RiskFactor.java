package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F4 风险安全分（领域纯函数，M20 方案 §4.2 + ADR-0058：ST 名称规则——A 股 5221 只名称含 ST（含 *ST）实测 201 只， 名称判定即可无需硬编码名单）：
 *
 * <pre>{@code
 * eventPenalty = Σ_{e∈E(s,W1), dir=BEARISH} riskTypeCoef × impCoef × 10 × decay(e)   （封顶 60）
 * stPenalty    = 名称含 "ST"（含 *ST）？ 40 : 0
 * F4           = clamp(100 − stPenalty − min(60, eventPenalty), 0, 100)
 * }</pre>
 *
 * <p>riskTypeCoef：监管处罚 1.5 / 业绩预告 1.2 / 回购变动 0.8 / 高管变动 0.5 / 其余 0.6；impCoef 取 heat-v1 三档
 * （1.0/0.5/0.25）。事件上限 60 + ST 40 → 下限 0。
 */
public final class RiskFactor {

    /** 事件惩罚封顶（与 ST 40 合成下限 0 的冻结拆分）。 */
    static final double EVENT_PENALTY_CAP = 60.0;

    /** ST 名称惩罚。 */
    static final double ST_PENALTY = 40.0;

    /** 单位放大（系数 ×10 使单条 HIGH 监管处罚即 15 分量级）。 */
    static final double PENALTY_UNIT = 10.0;

    private RiskFactor() {}

    /** F4 结果（score [0,100] + stFlag + 事件惩罚值 + 依据条目）。 */
    public record Result(
            double score, boolean stFlag, double eventPenalty, List<FactorEntry> entries) {}

    /** riskTypeCoef 冻结系数（其余类 0.6 兜底）。 */
    static double riskTypeCoef(EventType type) {
        return switch (type) {
            case REGULATORY_PENALTY -> 1.5;
            case EARNINGS_FORECAST -> 1.2;
            case BUYBACK_CHANGE -> 0.8;
            case EXEC_CHANGE -> 0.5;
            default -> 0.6;
        };
    }

    /** ST 名称判定（含 *ST；A 股命名规则为大写 ST——大小写敏感匹配）。 */
    public static boolean isStName(String subjectName) {
        return subjectName != null && subjectName.contains("ST");
    }

    /** 计算 F4（纯函数；窗口 W1 与半衰期锚定 snapshotDate）。 */
    public static Result compute(
            List<ValuationEvent> events,
            String subjectName,
            LocalDate snapshotDate,
            ValuationParams params) {
        double penalty = 0.0;
        List<FactorEntry> entries = new ArrayList<>();
        for (ValuationEvent event : events == null ? List.<ValuationEvent>of() : events) {
            long age =
                    FactorMath.ageInWindow(
                            event.eventDate(), snapshotDate, params.catalystWindowDays());
            if (age == FactorMath.OUT_OF_WINDOW || event.direction() != Direction.BEARISH) {
                continue;
            }
            double coef =
                    riskTypeCoef(event.eventType())
                            * event.importance().coefficient()
                            * PENALTY_UNIT;
            double decay = FactorMath.decay(age, params.halfLifeDays());
            penalty += coef * decay;
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
        boolean stFlag = isStName(subjectName);
        double cappedPenalty = Math.min(EVENT_PENALTY_CAP, penalty);
        double score = FactorMath.clampHundred(100.0 - (stFlag ? ST_PENALTY : 0.0) - cappedPenalty);
        return new Result(score, stFlag, penalty, cap(entries));
    }

    private static List<FactorEntry> cap(List<FactorEntry> entries) {
        return List.copyOf(entries.subList(0, Math.min(CatalystFactor.ENTRY_CAP, entries.size())));
    }
}
