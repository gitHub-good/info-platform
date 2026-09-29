package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.info.platform.application.markettop.HitStatsService.AggView;
import com.info.platform.application.markettop.HitStatsService.DayStatView;
import com.info.platform.application.markettop.HitStatsService.HitStatsView;
import com.info.platform.application.markettop.HitStatsService.WindowView;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.RankedSubject;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HitStatsService 惰性回算单测（M22 T193，方案 §3.4/§4.4-⑦ hits-v1 口径）：交易日窗推导（跳过无价日——库内交易日历代理）/
 * 有价样本口径（停牌/无价剔除计数不隐藏）/ 上涨家数占比 + 中位数涨跌幅（排序列插值）/ agg OK≥5 天与 INSUFFICIENT 如实 / 无榜单日 30089 / basis
 * 与免责常驻。仓储全 mock（窗口与聚合纯内存——对账 SQL 独立复算在集成测试）。
 */
class HitStatsServiceTest {

    /** 6 个榜单日（D1~D6）+ 1 个后续交易日（D7）——T+1 全窗可得、T+5 仅 2 天、T+20 全无。 */
    private static final List<String> TRADING_DATES =
            List.of(
                    "2026-09-01",
                    "2026-09-02",
                    "2026-09-03",
                    "2026-09-04",
                    "2026-09-05",
                    "2026-09-06",
                    "2026-09-07");

    private MarketTopRepository marketTopRepository;

    private MarketDailySnapshotRepository marketRepository;

    private HitStatsService service;

    @BeforeEach
    void setUp() {
        marketTopRepository = org.mockito.Mockito.mock(MarketTopRepository.class);
        marketRepository = org.mockito.Mockito.mock(MarketDailySnapshotRepository.class);
        service = new HitStatsService(marketTopRepository, marketRepository);
        when(marketRepository.findTradingDates(Market.A_SHARE)).thenReturn(TRADING_DATES);
    }

    // ---- 夹具：等比价格序列（s1 日涨 +10% / s2 日跌 −10% / s3 持平；D6 起 s3 停牌无价） ----

    private static Map<Long, Double> closes(double s1, double s2, Double s3) {
        Map<Long, Double> map = new HashMap<>();
        map.put(1L, s1);
        map.put(2L, s2);
        if (s3 != null) {
            map.put(3L, s3);
        }
        return map;
    }

    /** 价格序列：D_k 收盘 s1=100×1.1^k、s2=100×0.9^k、s3=50（D6 起停牌）。 */
    private void stubPrices() {
        when(marketRepository.findClosePrices("2026-09-01")).thenReturn(closes(100.0, 100.0, 50.0));
        when(marketRepository.findClosePrices("2026-09-02")).thenReturn(closes(110.0, 90.0, 50.0));
        when(marketRepository.findClosePrices("2026-09-03")).thenReturn(closes(121.0, 81.0, 50.0));
        when(marketRepository.findClosePrices("2026-09-04")).thenReturn(closes(133.1, 72.9, 50.0));
        when(marketRepository.findClosePrices("2026-09-05"))
                .thenReturn(closes(146.41, 65.61, 50.0));
        when(marketRepository.findClosePrices("2026-09-06"))
                .thenReturn(closes(161.051, 59.049, null));
        when(marketRepository.findClosePrices("2026-09-07"))
                .thenReturn(closes(177.1561, 53.1441, 50.0));
    }

    /** 各榜单日 Top = [s1, s2, s3]（最大 version 榜单已由仓储口径收口；入参 ASC → 喂桩转 DESC 契约序）。 */
    private void stubTops(String... rankDates) {
        List<RankedSubject> tops = new java.util.ArrayList<>();
        for (int i = rankDates.length - 1; i >= 0; i--) { // 仓储契约：rank_date 降序
            String date = rankDates[i];
            tops.add(new RankedSubject(date, 1, 1L));
            tops.add(new RankedSubject(date, 2, 2L));
            tops.add(new RankedSubject(date, 3, 3L));
        }
        when(marketTopRepository.listTopByMaxVersion(Market.A_SHARE)).thenReturn(tops);
    }

    private static WindowView windowOf(HitStatsView view, String window) {
        return view.windows().stream()
                .filter(w -> w.window().equals(window))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void stats_tPlus1_fullWindowWithPricedOnlySamplesAndPooledAgg() {
        stubTops(
                "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05", "2026-09-06");
        stubPrices();

        HitStatsView view = service.stats(null);

        WindowView t1 = windowOf(view, "T+1");
        assertThat(t1.days()).hasSize(6);
        DayStatView d1 = t1.days().get(0);
        assertThat(d1.rankDate()).isEqualTo("2026-09-01");
        assertThat(d1.topSize()).isEqualTo(3);
        assertThat(d1.pricedSamples()).isEqualTo(3);
        assertThat(d1.excluded()).isZero();
        assertThat(d1.upRatio()).isEqualTo(0.333); // 1/3（s1 +10% 唯一上涨）
        assertThat(d1.medianPctChg()).isEqualTo(0.0); // [−10, 0, 10] 中位 0
        // D5→D6：s3 目标日停牌 → 剔除计数不隐藏（N/10 样本标注原料）
        DayStatView d5 = t1.days().get(4);
        assertThat(d5.pricedSamples()).isEqualTo(2);
        assertThat(d5.excluded()).isEqualTo(1);
        assertThat(d5.upRatio()).isEqualTo(0.5);
        // agg：≥5 天 OK——池化口径（总上涨/总有价 = 6/16；全样本中位 0）
        assertThat(t1.agg().status()).isEqualTo("OK");
        assertThat(t1.agg().days()).isEqualTo(6);
        assertThat(t1.agg().upRatio()).isEqualTo(0.375);
        assertThat(t1.agg().medianPct()).isEqualTo(0.0);
    }

    @Test
    void stats_tPlus5_insufficientWhenDaysBelowFive() {
        stubTops(
                "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05", "2026-09-06");
        stubPrices();

        HitStatsView view = service.stats(null);

        // 仅 D1（目标 D6）/ D2（目标 D7）两窗可得 → 样本不足如实标注（INSUFFICIENT 态）
        WindowView t5 = windowOf(view, "T+5");
        assertThat(t5.days()).hasSize(2);
        assertThat(t5.agg().days()).isEqualTo(2);
        assertThat(t5.agg().status()).isEqualTo("INSUFFICIENT");
        assertThat(t5.agg().upRatio()).isNull();
        assertThat(t5.agg().medianPct()).isNull();
        // D1→D6：s1 +61.05% / s2 −40.95%（五日等比累积）——中位 = 插值平均 10.05
        DayStatView d1 = t5.days().get(0);
        assertThat(d1.pricedSamples()).isEqualTo(2); // s3 目标日无价剔除
        assertThat(d1.medianPctChg()).isEqualTo(10.05);
    }

    @Test
    void stats_tPlus20_notArrived_emptyDaysInsufficient() {
        stubTops("2026-09-01");
        stubPrices();

        HitStatsView view = service.stats(null);

        WindowView t20 = windowOf(view, "T+20");
        assertThat(t20.days()).isEmpty();
        assertThat(t20.agg().days()).isZero();
        assertThat(t20.agg().status()).isEqualTo("INSUFFICIENT"); // 首跑校准条款：不硬凑
    }

    @Test
    void stats_tradingWindowSkipsNoPriceDays() {
        // 交易日序列跳过无价日（周末/停市）——T+1 目标 = 下一有价日 09-07（非自然日 09-05）
        when(marketRepository.findTradingDates(Market.A_SHARE))
                .thenReturn(List.of("2026-09-04", "2026-09-07", "2026-09-08"));
        when(marketRepository.findClosePrices("2026-09-04")).thenReturn(Map.of(1L, 100.0));
        when(marketRepository.findClosePrices("2026-09-07")).thenReturn(Map.of(1L, 105.0));
        when(marketRepository.findClosePrices("2026-09-08")).thenReturn(Map.of(1L, 200.0));
        stubTops("2026-09-04");

        HitStatsView view = service.stats(null);

        DayStatView day = windowOf(view, "T+1").days().get(0);
        assertThat(day.pricedSamples()).isEqualTo(1);
        assertThat(day.medianPctChg()).isEqualTo(5.0); // 105/100−1——证目标日 = 09-07
        // T+5：序列仅 3 日 → 窗口未满 INSUFFICIENT
        assertThat(windowOf(view, "T+5").days()).isEmpty();
    }

    @Test
    void stats_rankDateWithoutBasePrice_skipped() {
        // 榜单日不在交易日序列（无基期价格）→ 该日不可算不计入 days
        stubTops("2026-09-05", "2026-10-01");
        stubPrices();

        HitStatsView view = service.stats(null);

        assertThat(windowOf(view, "T+1").days())
                .extracting(DayStatView::rankDate)
                .containsExactly("2026-09-05");
    }

    @Test
    void stats_noRankDays_throws30089() {
        when(marketTopRepository.listTopByMaxVersion(Market.A_SHARE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.stats(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_NOT_FOUND));
    }

    @Test
    void stats_basisDisclaimerAndAsOfBaked() {
        stubTops("2026-09-06");
        stubPrices();

        HitStatsView view = service.stats(null);

        // 口径留档（hits-v1）+ 免责常驻 + asOf = 最新交易日
        assertThat(view.basis())
                .isEqualTo(
                        "hits-v1:maxVer;price=market_daily_snapshot;win=1/5/20;median=pctChg;sample=priced-only");
        assertThat(view.disclaimer()).isEqualTo("历史统计不构成收益承诺");
        assertThat(view.asOf()).isEqualTo("2026-09-07");
        assertThat(view.windows())
                .extracting(WindowView::window)
                .containsExactly("T+1", "T+5", "T+20");
    }

    @Test
    void stats_allPricedFlat_medianZeroUpRatioZero() {
        when(marketRepository.findTradingDates()).thenReturn(List.of("2026-09-01", "2026-09-02"));
        when(marketRepository.findClosePrices("2026-09-01")).thenReturn(Map.of(1L, 50.0, 2L, 60.0));
        when(marketRepository.findClosePrices("2026-09-02")).thenReturn(Map.of(1L, 50.0, 2L, 60.0));
        stubTops("2026-09-01");

        AggView agg = windowOf(service.stats(null), "T+1").agg();

        // 全平：上涨 0 家（严格 > 0）占比 0、中位 0——信号验证口径零漂移面
        assertThat(agg.status()).isEqualTo("INSUFFICIENT"); // 1 天 < 5
        DayStatView day = windowOf(service.stats(null), "T+1").days().get(0);
        assertThat(day.upRatio()).isZero();
        assertThat(day.medianPctChg()).isZero();
    }

    @Test
    void stats_marketParam_hkReadsHkTradingDatesAndTops() {
        // M29 T256：market=HK 走 HK 交易日序列与 HK 榜单（不与 A 股混序混榜）+ market 回显
        when(marketTopRepository.listTopByMaxVersion(Market.HK))
                .thenReturn(List.of(new RankedSubject("2026-09-02", 1, 9L)));
        when(marketRepository.findTradingDates(Market.HK))
                .thenReturn(List.of("2026-09-01", "2026-09-02", "2026-09-03"));
        when(marketRepository.findClosePrices("2026-09-02")).thenReturn(Map.of(9L, 100.0));
        when(marketRepository.findClosePrices("2026-09-03")).thenReturn(Map.of(9L, 110.0));

        HitStatsView view = service.stats("HK");

        assertThat(view.market()).isEqualTo("HK");
        WindowView t1 = windowOf(view, "T+1");
        assertThat(t1.days()).hasSize(1);
        assertThat(t1.days().get(0).rankDate()).isEqualTo("2026-09-02");
        assertThat(t1.days().get(0).upRatio()).isEqualTo(1.0);
    }
}
