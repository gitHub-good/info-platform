package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F3 基本面边际单测（T170，方案 §4.2）：无信号 50 中性、tanh 对称（±1.5 → 88/12）、带符号方向（NEUTRAL +0.2/BEARISH −1）、
 * typeCoef3 五类、非基本面类剔除、窗界与衰减。
 */
class FundamentalFactorTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private static final ValuationParams PARAMS = ValuationParams.defaults();

    private static ValuationEvent event(
            long id, EventType type, Direction direction, Importance importance, int ageDays) {
        return new ValuationEvent(
                id, "事件" + id, SNAPSHOT.minusDays(ageDays), direction, importance, type);
    }

    @Test
    void noSignal_neutralFifty() {
        FundamentalFactor.Result result = FundamentalFactor.compute(List.of(), SNAPSHOT, PARAMS);

        assertThat(result.score()).isEqualTo(50.0);
        assertThat(result.raw()).isZero();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void plusOnePointFiveWeighted_reachesEightyEight() {
        // 1.5 个加权单位（HIGH BULLISH EARNINGS + MEDIUM BULLISH BUYBACK(0.8)×0.5=0.4 → 需再 0.1?）
        // 精确 1.5：HIGH BULLISH EARNINGS(1.0) + HIGH BULLISH MAJOR_CONTRACT(0.7)×decay0.5=0.35
        // + MEDIUM BULLISH EARNINGS(0.5)×0.3? —— 改用单事件可精确控制的组合：
        // EARNINGS(1.0×1.0) + MA_MERGER(0.5×1.0) = 1.5（两条 HIGH BULLISH age0）
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.MA_MERGER,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isEqualTo(1.5);
        assertThat(result.score()).isCloseTo(88.08, within(0.01)); // 50+50×tanh(1)
    }

    @Test
    void tanhSymmetric_minusOnePointFive_reachesTwelve() {
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BEARISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.MA_MERGER,
                                        Direction.BEARISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isEqualTo(-1.5);
        assertThat(result.score()).isCloseTo(11.92, within(0.01)); // 50−50×tanh(1)
    }

    @Test
    void neutralDirection_weighsPlusZeroPointTwo() {
        // HIGH NEUTRAL BUYBACK(0.8)：sign 0.2 × 0.8 × 1.0 = 0.16 → 50+50×tanh(0.16/1.5)
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.BUYBACK_CHANGE,
                                        Direction.NEUTRAL,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isCloseTo(0.16, within(1e-9));
        assertThat(result.score()).isCloseTo(55.31, within(0.01));
    }

    @Test
    void nonFundamentalTypes_excluded() {
        // POLICY_RELEASE / REGULATORY_PENALTY / EXEC_CHANGE / OTHER 不进基本面维
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.POLICY_RELEASE,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.REGULATORY_PENALTY,
                                        Direction.BEARISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        3,
                                        EventType.EXEC_CHANGE,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(4, EventType.OTHER, Direction.BULLISH, Importance.HIGH, 0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.score()).isEqualTo(50.0);
    }

    @Test
    void typeCoefLadder_evidenceWeighed() {
        // 五类系数 1.0/0.8/0.7/0.5/0.4（EARNINGS/BUYBACK/CONTRACT/MA_MERGER/TECH），全 HIGH BULLISH age0
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.BUYBACK_CHANGE,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        3,
                                        EventType.MAJOR_CONTRACT,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        4,
                                        EventType.MA_MERGER,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        5,
                                        EventType.TECH_BREAKTHROUGH,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isCloseTo(3.4, within(1e-9)); // 1.0+0.8+0.7+0.5+0.4
        assertThat(result.entries()).hasSize(5);
        assertThat(result.entries().get(0).eventId()).isEqualTo(1); // 贡献降序
    }

    @Test
    void decayAndWindow_excludedBeyondCatalystWindow() {
        // W1=10：age9 在窗（decay 0.5^(9/5)=0.2917），age10 越窗
        FundamentalFactor.Result inside =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        9)),
                        SNAPSHOT,
                        PARAMS);
        FundamentalFactor.Result outside =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        10)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(inside.raw()).isCloseTo(Math.pow(0.5, 9.0 / 5.0), within(1e-9));
        assertThat(outside.score()).isEqualTo(50.0);
    }

    @Test
    void mixedSigns_netOut() {
        // +1.0（HIGH BULLISH EARNINGS）−1.0×0.5^0.4? 改精确：−0.8（HIGH BEARISH BUYBACK）→ raw 0.2
        FundamentalFactor.Result result =
                FundamentalFactor.compute(
                        List.of(
                                event(
                                        1,
                                        EventType.EARNINGS_FORECAST,
                                        Direction.BULLISH,
                                        Importance.HIGH,
                                        0),
                                event(
                                        2,
                                        EventType.BUYBACK_CHANGE,
                                        Direction.BEARISH,
                                        Importance.HIGH,
                                        0)),
                        SNAPSHOT,
                        PARAMS);

        assertThat(result.raw()).isCloseTo(0.2, within(1e-9));
        assertThat(result.score()).isGreaterThan(50.0);
        // 负贡献条目也入 entries（依据可查），按 |贡献| 降序
        assertThat(result.entries()).hasSize(2);
    }
}
