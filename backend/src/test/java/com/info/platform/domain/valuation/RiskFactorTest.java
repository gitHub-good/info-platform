package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F4 风险安全分单测（T170，方案 §4.2 + ADR-0058 裁决：ST 名称规则 201 只实测背书）：无险 100、ST/含*ST 扣 40、 BEARISH 密度惩罚
 * riskTypeCoef×impCoef×10×decay、事件惩罚 60 封顶、组合下限 clamp 0、非利空不计。
 */
class RiskFactorTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private static final ValuationParams PARAMS = ValuationParams.defaults();

    private static ValuationEvent bearish(
            long id, EventType type, Importance importance, int ageDays) {
        return new ValuationEvent(
                id, "事件" + id, SNAPSHOT.minusDays(ageDays), Direction.BEARISH, importance, type);
    }

    @Test
    void noRiskSignal_fullHundred() {
        RiskFactor.Result result = RiskFactor.compute(List.of(), "贵州茅台", SNAPSHOT, PARAMS);

        assertThat(result.score()).isEqualTo(100.0);
        assertThat(result.stFlag()).isFalse();
        assertThat(result.eventPenalty()).isZero();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void stName_fortyPenalty() {
        assertThat(RiskFactor.compute(List.of(), "ST易联众", SNAPSHOT, PARAMS).score())
                .isEqualTo(60.0);
        assertThat(RiskFactor.compute(List.of(), "*ST金科", SNAPSHOT, PARAMS).score())
                .isEqualTo(60.0);
        assertThat(RiskFactor.compute(List.of(), "*ST金科", SNAPSHOT, PARAMS).stFlag()).isTrue();
        // 非 ST 名不受牵连（大小写敏感：A 股 ST 命名规则为大写）
        assertThat(RiskFactor.compute(List.of(), "平安银行", SNAPSHOT, PARAMS).score())
                .isEqualTo(100.0);
    }

    @Test
    void singleRegulatoryPenalty_fifteenPenalty() {
        // HIGH BEARISH REGULATORY_PENALTY：1.5×1.0×10×1.0 = 15 → F4 = 85
        RiskFactor.Result result =
                RiskFactor.compute(
                        List.of(bearish(1, EventType.REGULATORY_PENALTY, Importance.HIGH, 0)),
                        "平安银行",
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.eventPenalty()).isEqualTo(15.0);
        assertThat(result.score()).isEqualTo(85.0);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).coef()).isEqualTo(15.0); // riskTypeCoef×impCoef×10
    }

    @Test
    void riskTypeCoefLadder() {
        // EARNINGS_FORECAST 1.2 / BUYBACK 0.8 / EXEC_CHANGE 0.5 / 其余（MA_MERGER 等）0.6——MEDIUM(0.5)
        RiskFactor.Result result =
                RiskFactor.compute(
                        List.of(
                                bearish(1, EventType.EARNINGS_FORECAST, Importance.MEDIUM, 0),
                                bearish(2, EventType.BUYBACK_CHANGE, Importance.MEDIUM, 0),
                                bearish(3, EventType.EXEC_CHANGE, Importance.MEDIUM, 0),
                                bearish(4, EventType.MA_MERGER, Importance.MEDIUM, 0)),
                        "平安银行",
                        SNAPSHOT,
                        PARAMS);

        // (1.2+0.8+0.5+0.6)×0.5×10 = 15.5
        assertThat(result.eventPenalty()).isCloseTo(15.5, within(1e-9));
    }

    @Test
    void eventPenalty_cappedAtSixty() {
        // 5 条 HIGH BEARISH REGULATORY_PENALTY age0 → 75 → 封顶 60 → F4 = 40
        List<ValuationEvent> events = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            events.add(bearish(i, EventType.REGULATORY_PENALTY, Importance.HIGH, 0));
        }

        RiskFactor.Result result = RiskFactor.compute(events, "平安银行", SNAPSHOT, PARAMS);

        assertThat(result.eventPenalty()).isEqualTo(75.0);
        assertThat(result.score()).isEqualTo(40.0);
    }

    @Test
    void stPlusCappedEvents_clampedToZero() {
        List<ValuationEvent> events = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            events.add(bearish(i, EventType.REGULATORY_PENALTY, Importance.HIGH, 0));
        }

        RiskFactor.Result result = RiskFactor.compute(events, "ST易联众", SNAPSHOT, PARAMS);

        assertThat(result.score()).isZero(); // 100 − 40 − 60 → clamp 0
    }

    @Test
    void nonBearishEvents_notPenalized() {
        // BULLISH/NEUTRAL 不进风险维（归 F1/F3——蓝图裁决 1 分工）
        RiskFactor.Result result =
                RiskFactor.compute(
                        List.of(
                                new ValuationEvent(
                                        1,
                                        "利好",
                                        SNAPSHOT,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        EventType.REGULATORY_PENALTY),
                                new ValuationEvent(
                                        2,
                                        "中性",
                                        SNAPSHOT,
                                        Direction.NEUTRAL,
                                        Importance.HIGH,
                                        EventType.EARNINGS_FORECAST)),
                        "平安银行",
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.eventPenalty()).isZero();
        assertThat(result.score()).isEqualTo(100.0);
    }

    @Test
    void decayApplied_eventOutsideWindowIgnored() {
        // age=10 越窗不计；age=5（decay 0.5）：1.5×1.0×10×0.5 = 7.5
        RiskFactor.Result inside =
                RiskFactor.compute(
                        List.of(bearish(1, EventType.REGULATORY_PENALTY, Importance.HIGH, 5)),
                        "平安银行",
                        SNAPSHOT,
                        PARAMS);
        RiskFactor.Result outside =
                RiskFactor.compute(
                        List.of(bearish(1, EventType.REGULATORY_PENALTY, Importance.HIGH, 10)),
                        "平安银行",
                        SNAPSHOT,
                        PARAMS);

        assertThat(inside.eventPenalty()).isEqualTo(7.5);
        assertThat(outside.eventPenalty()).isZero();
    }

    @Test
    void entries_cappedAtTenByPenaltyDesc() {
        List<ValuationEvent> events = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            events.add(bearish(i, EventType.REGULATORY_PENALTY, Importance.HIGH, i));
        }
        events.add(bearish(99, EventType.REGULATORY_PENALTY, Importance.HIGH, 3));

        RiskFactor.Result result = RiskFactor.compute(events, "平安银行", SNAPSHOT, PARAMS);

        assertThat(result.entries()).hasSize(10); // 11 条在窗截尾至 10
        assertThat(result.entries().get(0).eventId()).isEqualTo(0); // age0 惩罚最大
    }
}
