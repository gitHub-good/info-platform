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
 * F1 事件催化强度单测（T170，方案 §4.2 公式逐字）：impCoef/dirCoef/typeCoef1 三系数、半衰期衰减边界（age=0/age=hl/age=窗界）、
 * BEARISH 不进催化维、LOW 不进窗、饱和映射 K1=3.0、entries cap 10 按贡献降序。锚定 snapshot_date（非当前时刻）。
 */
class CatalystFactorTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private static final ValuationParams PARAMS = ValuationParams.defaults();

    private static ValuationEvent event(
            long id, EventType type, Direction direction, Importance importance, int ageDays) {
        return new ValuationEvent(
                id, "事件" + id, SNAPSHOT.minusDays(ageDays), direction, importance, type);
    }

    @Test
    void emptyWindow_scoreZero() {
        CatalystFactor.Result result = CatalystFactor.compute(List.of(), SNAPSHOT, PARAMS);

        assertThat(result.score()).isZero();
        assertThat(result.raw()).isZero();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void singleHighBullishEarningsAgeZero_scoresQuarterOfSaturation() {
        // raw1 = 1.0×1.0×1.0×1.0 = 1.0 → 100×1/(1+3) = 25
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isEqualTo(1.0);
        assertThat(result.score()).isEqualTo(25.0);
    }

    @Test
    void mediumImportanceAndTypeCoef_weighed() {
        // MEDIUM(0.5) × BULLISH(1.0) × EXEC_CHANGE(0.4) × decay1.0 = 0.2 → 100×0.2/3.2 = 6.25
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EXEC_CHANGE,
                                        Direction.BULLISH,
                                        Importance.MEDIUM,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isEqualTo(0.2);
        assertThat(result.score()).isEqualTo(6.25);
    }

    @Test
    void neutralDirection_weighedByPointThree() {
        // HIGH(1.0) × NEUTRAL(0.3) × MAJOR_CONTRACT(1.0) = 0.3 → 100×0.3/3.3
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.MAJOR_CONTRACT,
                                        Direction.NEUTRAL,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isEqualTo(0.3);
        assertThat(result.score()).isCloseTo(9.0909, within(1e-4));
    }

    @Test
    void bearishAndLowImportance_excludedFromCatalyst() {
        // 利空归 F4（dirCoef=0），LOW 不进 F1 窗（§4.2 imp ∈ {HIGH,MEDIUM}）
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BEARISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.LOW,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isZero();
        assertThat(result.score()).isZero();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void decayBoundary_ageZeroAndHalfLife() {
        CatalystFactor.Result ageZero =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);
        CatalystFactor.Result ageHalfLife =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        5)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(ageZero.raw()).isEqualTo(1.0);
        // age = hl(5d) → decay = 0.5 → raw 0.5 → 100×0.5/3.5 ≈ 14.2857
        assertThat(ageHalfLife.raw()).isEqualTo(0.5);
        assertThat(ageHalfLife.score()).isCloseTo(14.2857, within(1e-4));
        assertThat(ageHalfLife.entries().get(0).decay()).isEqualTo(0.5);
    }

    @Test
    void windowBoundary_ageJustInsideAndOutside() {
        CatalystFactor.Result inside =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        9)),
                        SNAPSHOT,
                        PARAMS);
        CatalystFactor.Result outside =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        10)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(inside.entries()).hasSize(1); // age 9 < W1(10) 在窗
        assertThat(outside.entries()).isEmpty(); // age 10 越窗剔除
        assertThat(outside.score()).isZero();
    }

    @Test
    void futureDatedEvent_defensivelyExcluded() {
        // event_date > snapshot_date（age<0）防御剔除——锚定 snapshot_date 的幂等基石
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        -1)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.score()).isZero();
    }

    @Test
    void multiEvent_summedThenSaturated() {
        // HIGH BULLISH EARNINGS age0（1.0）+ MEDIUM BULLISH TECH(0.9) age5（0.5×0.9×0.5=0.225）
        // → raw1 = 1.225 → 100×1.225/(1.225+3) ≈ 28.9940
        CatalystFactor.Result result =
                CatalystFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.TECH_BREAKTHROUGH,
                                        Direction.BULLISH,
                                        Importance.MEDIUM,
                                        5)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isCloseTo(1.225, within(1e-9));
        assertThat(result.score()).isCloseTo(28.9940, within(1e-4));
    }

    @Test
    void entries_cappedAtTenSortedByContributionDesc() {
        List<ValuationEvent> events = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            // 贡献随 age 递增而递减（decay 单调）；age10 越窗 → 恰 10 条在窗全保留后截尾
            events.add(
                    event(i, EventType.EARNINGS_FORECAST, Direction.BULLISH, Importance.HIGH, i));
        }
        events.add(event(99, EventType.EARNINGS_FORECAST, Direction.BULLISH, Importance.HIGH, 3));

        CatalystFactor.Result result = CatalystFactor.compute(events, SNAPSHOT, PARAMS);

        assertThat(result.entries()).hasSize(10); // 11 条在窗截尾至 10
        assertThat(result.entries().get(0).eventId()).isEqualTo(0); // age0 贡献最大
        // age3 两事件（id 3 与 99）并列贡献 → eventId 升序破并列（确定性）
        assertThat(result.entries().get(3).eventId()).isEqualTo(3);
        assertThat(result.entries().get(4).eventId()).isEqualTo(99);
        // 非升序（并列贡献相邻——由上方 eventId 破并列断言保证确定性）
        for (int i = 1; i < result.entries().size(); i++) {
            double prev = result.entries().get(i - 1).coef() * result.entries().get(i - 1).decay();
            double curr = result.entries().get(i).coef() * result.entries().get(i).decay();
            assertThat(prev).isGreaterThanOrEqualTo(curr);
        }
    }
}
