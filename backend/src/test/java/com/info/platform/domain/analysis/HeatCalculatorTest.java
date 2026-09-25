package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HeatCalculator 单测（T123，方案 §3.6/§4.5 裁决 6）：公式代入（1 + K1×impCoef）、K1 与 impCoef
 * 权重矩阵、指数半衰期衰减矩阵（12h|48h 双轨）、容器条目不进榜但事件扩散计入、REQ 场景 2（事件加权不输纯条数）/场景 3（衰减压制昨热）、prev 环比边界、basis
 * 串。纯函数。AAA 结构。
 */
class HeatCalculatorTest {

    private static final Instant END = Instant.parse("2026-09-22T08:00:00Z");

    private static final HeatCalculator.HeatParams DEFAULTS = HeatCalculator.HeatParams.defaults();

    private static HeatCalculator.HeatItem item(String main, String publishedAtIso) {
        return new HeatCalculator.HeatItem(main, Instant.parse(publishedAtIso), null, null);
    }

    private static HeatCalculator.HeatItem eventItem(
            String main, String publishedAtIso, Importance importance, String... affected) {
        return new HeatCalculator.HeatItem(
                main, Instant.parse(publishedAtIso), importance, List.of(affected));
    }

    private static Map<String, HeatCalculator.IndustryHeat> computeH24(
            List<HeatCalculator.HeatItem> items) {
        return HeatCalculator.compute(items, DEFAULTS, END, HeatWindow.H24.length());
    }

    @Test
    void compute_plainItems_freshCountSum() {
        // 3 条同行业、窗口终点发布（age=0 → 衰减 1）→ score = 3、newsCount = 3、eventCount = 0
        Map<String, HeatCalculator.IndustryHeat> board =
                computeH24(
                        List.of(
                                item("银行", "2026-09-22T08:00:00Z"),
                                item("银行", "2026-09-22T08:00:00Z"),
                                item("银行", "2026-09-22T08:00:00Z")));

        assertThat(board.get("银行").score()).isEqualTo(3.0);
        assertThat(board.get("银行").newsCount()).isEqualTo(3);
        assertThat(board.get("银行").eventCount()).isZero();
    }

    @Test
    void compute_ownIndustryEvent_weightIsOnePlusK1TimesCoef() {
        // 自行业事件：条目贡献 (1 + K1×impCoef)——HIGH=11、MEDIUM=6、LOW=3.5（K1=10）
        Map<String, HeatCalculator.IndustryHeat> board =
                computeH24(
                        List.of(
                                eventItem("银行", "2026-09-22T08:00:00Z", Importance.HIGH, "银行"),
                                eventItem("房地产", "2026-09-22T08:00:00Z", Importance.MEDIUM, "房地产"),
                                eventItem("汽车", "2026-09-22T08:00:00Z", Importance.LOW, "汽车")));

        assertThat(board.get("银行").score()).isEqualTo(1.0 + 10.0 * 1.0);
        assertThat(board.get("房地产").score()).isEqualTo(1.0 + 10.0 * 0.5);
        assertThat(board.get("汽车").score()).isEqualTo(1.0 + 10.0 * 0.25);
        // 自行业事件计 eventCount（含直接命中）
        assertThat(board.get("银行").eventCount()).isEqualTo(1);
        assertThat(board.get("银行").newsCount()).isEqualTo(1);
    }

    @Test
    void compute_containerItemSpreadsEventWeight_onlyViaAffected() {
        // 容器条目（宏观）不进榜：news_count 不计；事件扩散到受影响行业拿 K1×impCoef（REQ「降准」语义）
        Map<String, HeatCalculator.IndustryHeat> board =
                computeH24(
                        List.of(
                                eventItem(
                                        "宏观",
                                        "2026-09-22T08:00:00Z",
                                        Importance.HIGH,
                                        "银行",
                                        "房地产")));

        assertThat(board).doesNotContainKey("宏观"); // 容器不进榜（结果恒为申万 31 全量键）
        assertThat(board).hasSize(31);
        assertThat(board.get("银行").score()).isEqualTo(10.0 * 1.0);
        assertThat(board.get("房地产").score()).isEqualTo(10.0 * 1.0);
        assertThat(board.get("银行").newsCount()).isZero(); // 容器条目不计行业资讯量
        assertThat(board.get("银行").eventCount()).isEqualTo(1);
        assertThat(board.get("食品饮料").score()).isZero(); // 无关行业零填常驻
    }

    @Test
    void compute_crossIndustryEvent_noDoubleCountOnOwnIndustry() {
        // 条目 main=电子、事件 affected=[电子, 通信]：电子得 (1+K1)（第一项，不双计 spread）、通信得 K1（第二项）
        Map<String, HeatCalculator.IndustryHeat> board =
                computeH24(
                        List.of(
                                eventItem(
                                        "电子",
                                        "2026-09-22T08:00:00Z",
                                        Importance.HIGH,
                                        "电子",
                                        "通信")));

        assertThat(board.get("电子").score()).isEqualTo(1.0 + 10.0 * 1.0);
        assertThat(board.get("通信").score()).isEqualTo(10.0 * 1.0);
        assertThat(board.get("电子").eventCount()).isEqualTo(1);
        assertThat(board.get("通信").eventCount()).isEqualTo(1);
        assertThat(board.get("电子").newsCount()).isEqualTo(1);
        assertThat(board.get("通信").newsCount()).isZero(); // 通信只收事件扩散，不收资讯量
    }

    @Test
    void compute_halfLifeDecayMatrix_bothWindows() {
        // H24 窗（hl=12h）：0h→1、12h→0.5、24h→0.25；D7 窗（hl=48h）：24h→~0.707
        Map<String, HeatCalculator.IndustryHeat> h24 =
                computeH24(
                        List.of(
                                item("银行", "2026-09-22T08:00:00Z"),
                                item("食品饮料", "2026-09-21T20:00:00Z"), // 12h 前
                                item("汽车", "2026-09-21T08:00:00Z"))); // 24h 前
        assertThat(h24.get("银行").score()).isEqualTo(1.0);
        assertThat(h24.get("食品饮料").score()).isCloseTo(0.5, within(1e-9));
        assertThat(h24.get("汽车").score()).isCloseTo(0.25, within(1e-9));

        Map<String, HeatCalculator.IndustryHeat> d7 =
                HeatCalculator.compute(
                        List.of(item("银行", "2026-09-21T08:00:00Z")),
                        DEFAULTS,
                        END,
                        HeatWindow.D7.length());
        assertThat(d7.get("银行").score()).isCloseTo(Math.pow(0.5, 24.0 / 48.0), within(1e-9));
    }

    @Test
    void compute_itemsOutsideWindow_skippedDefensively() {
        // 窗口外（≥ 终点 / < 起点）双保险跳过
        Map<String, HeatCalculator.IndustryHeat> board =
                computeH24(
                        List.of(
                                item("银行", "2026-09-22T08:00:01Z"), // 终点后（不含）
                                item("汽车", "2026-09-20T08:00:00Z"))); // 早于 24h 窗起点

        assertThat(board.get("银行").score()).isZero();
        assertThat(board.get("汽车").score()).isZero();
    }

    @Test
    void compute_reqScenario2_eventWeightedBeatsPlainCount_sameFreshness() {
        // REQ 场景 2（冻结验收）：A 10 条（8 纯 + 2 含高事件）≥ B 30 条纯，同新鲜度 → A = 8+2×11 = 30 ≥ 30
        java.util.List<HeatCalculator.HeatItem> a = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            a.add(item("银行", "2026-09-22T08:00:00Z"));
        }
        a.add(eventItem("银行", "2026-09-22T08:00:00Z", Importance.HIGH, "银行"));
        a.add(eventItem("银行", "2026-09-22T08:00:00Z", Importance.HIGH, "银行"));
        java.util.List<HeatCalculator.HeatItem> b = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            b.add(item("房地产", "2026-09-22T08:00:00Z"));
        }

        Map<String, HeatCalculator.IndustryHeat> board = computeH24(java.util.List.copyOf(a));
        Map<String, HeatCalculator.IndustryHeat> boardB = computeH24(java.util.List.copyOf(b));

        assertThat(board.get("银行").score()).isEqualTo(30.0);
        assertThat(boardB.get("房地产").score()).isEqualTo(30.0);
        assertThat(board.get("银行").score()).isGreaterThanOrEqualTo(boardB.get("房地产").score());
    }

    @Test
    void compute_reqScenario3_decaySuppressesYesterdayHeat() {
        // REQ 场景 3：昨热（24h 前集中 30 条）被衰减压制（×0.25 → 7.5）——今热（含事件）反超
        java.util.List<HeatCalculator.HeatItem> yesterday = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            yesterday.add(item("房地产", "2026-09-21T08:00:00Z"));
        }
        Map<String, HeatCalculator.IndustryHeat> board = computeH24(yesterday);

        assertThat(board.get("房地产").score()).isCloseTo(30.0 * 0.25, within(1e-9));
        assertThat(board.get("房地产").score()).isLessThan(10.0); // 被一条高事件行业（≥11）反超的量级
    }

    @Test
    void compute_configurableParams_takeEffect() {
        // K1 热改（basis 随快照升版的参数面）：K1=3 时自行业高事件 = 1+3
        HeatCalculator.HeatParams k1of3 =
                new HeatCalculator.HeatParams(3.0, 1.0, 0.5, 0.25, 12.0, 48.0);
        Map<String, HeatCalculator.IndustryHeat> board =
                HeatCalculator.compute(
                        List.of(eventItem("银行", "2026-09-22T08:00:00Z", Importance.HIGH, "银行")),
                        k1of3,
                        END,
                        HeatWindow.H24.length());

        assertThat(board.get("银行").score()).isEqualTo(4.0);
    }

    @Test
    void deltaPct_prevZeroEdges() {
        // prev=0 且 score>0 记 100.0；双 0 记 0；常规公式含负向
        assertThat(HeatCalculator.deltaPct(5.0, 0.0)).isEqualTo(100.0);
        assertThat(HeatCalculator.deltaPct(0.0, 0.0)).isEqualTo(0.0);
        assertThat(HeatCalculator.deltaPct(0.0, 10.0)).isEqualTo(-100.0);
        assertThat(HeatCalculator.deltaPct(15.0, 10.0)).isEqualTo(50.0);
        assertThat(HeatCalculator.deltaPct(5.0, 10.0)).isEqualTo(-50.0);
    }

    @Test
    void basis_defaultsExactString() {
        // 口径版本串（参数热改后首快照起换串）
        assertThat(HeatCalculator.basis(DEFAULTS))
                .isEqualTo("heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h");
        assertThat(
                        HeatCalculator.basis(
                                new HeatCalculator.HeatParams(3.5, 1.0, 0.5, 0.25, 12.0, 48.0)))
                .isEqualTo("heat-v1:k1=3.5;imp=1.0/0.5/0.25;hl=12h|48h");
    }

    @Test
    void entity_rejectsContainerIndustry() {
        // 实体把守：容器枚举进快照直接拒绝（62 行常驻只含申万 31）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                IndustryHeatSnapshot.create(
                                        "宏观", HeatWindow.H24, 1.0, 0.0, 1, 0, "heat-v1:...", END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("申万");
    }

    @Test
    void window_parsingAndLength() {
        assertThat(HeatWindow.fromName("h24")).isEqualTo(HeatWindow.H24);
        assertThat(HeatWindow.fromName("D7")).isEqualTo(HeatWindow.D7);
        assertThat(HeatWindow.fromName(null)).isNull();
        assertThat(HeatWindow.fromName("W1")).isNull();
        assertThat(HeatWindow.H24.length()).isEqualTo(Duration.ofHours(24));
        assertThat(HeatWindow.D7.length()).isEqualTo(Duration.ofDays(7));
    }
}
