package com.info.platform.application.markettop;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.RankedSubject;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史命中统计惰性回算服务（应用层，M22 T193，方案 §3.4 + ADR-0061 裁决 4——零新表零新 Job，读时现算永远新鲜）。
 *
 * <p><b>hits-v1 口径</b>（basis 串留档）：榜单日取当日最大 version（EVENT 版本计入——日终语义 = 最后立场）；交易日序列 =
 * market_daily_snapshot 去重 snapshot_date 升序（库内交易日历代理，无价日天然跳过）；窗口目标日 = 榜单日后第 1/5/20 个交易日； 涨跌幅 = (目标日
 * close − 榜单日 close) / 榜单日 close ×100；有价样本 = 两端 close 均非 NULL（停牌/无价剔除计数标注不隐藏）； 上涨 = 涨跌幅严格 &gt;
 * 0；中位数 = 排序列插值（偶数取中间两均值）；窗口目标日未到来 → 该窗 INSUFFICIENT（首跑校准条款如实标注）。
 */
@Service
public class HitStatsService {

    /** 口径指纹（hits-v1——前端与方法论页口径对齐断言依据）。 */
    static final String BASIS =
            "hits-v1:maxVer;price=market_daily_snapshot;win=1/5/20;median=pctChg;sample=priced-only";

    /** 统计面免责常驻（需求拍板五：自用统计非对外宣传）。 */
    static final String DISCLAIMER = "历史统计不构成收益承诺";

    /** 聚合态 OK 下限（样本日 &lt; 5 → INSUFFICIENT——个人量级防小样本误导）。 */
    static final int MIN_AGG_DAYS = 5;

    private static final List<Integer> WINDOW_PLUS_DAYS = List.of(1, 5, 20);

    private static final Logger log = LoggerFactory.getLogger(HitStatsService.class);

    private final MarketTopRepository marketTopRepository;

    private final MarketDailySnapshotRepository marketRepository;

    public HitStatsService(
            MarketTopRepository marketTopRepository,
            MarketDailySnapshotRepository marketRepository) {
        this.marketTopRepository = marketTopRepository;
        this.marketRepository = marketRepository;
    }

    /**
     * 命中统计（GET /api/v1/market-top/hit-stats，数据不足是合法态非错误——仅无任何榜单日 30089）。
     *
     * <p>容量：180 天留痕上限 ~180×10 行 × 3 窗逐日 join，逐日收盘截面按需现读（同日截面跨窗复用），毫秒级（方案 §5）。
     */
    public HitStatsView stats() {
        List<RankedSubject> tops = marketTopRepository.listTopByMaxVersion();
        if (tops.isEmpty()) {
            throw new BusinessException(ErrorCode.MARKET_TOP_NOT_FOUND, "无任何榜单日（Job 未跑过）");
        }
        List<String> tradingDates = marketRepository.findTradingDates();
        Map<String, Integer> indexByDate = new HashMap<>();
        for (int i = 0; i < tradingDates.size(); i++) {
            indexByDate.put(tradingDates.get(i), i);
        }
        Map<String, List<Long>> topByDate = new LinkedHashMap<>(); // rank_date 降序入表（tops 序）
        for (RankedSubject top : tops) {
            topByDate
                    .computeIfAbsent(top.rankDate(), date -> new ArrayList<>())
                    .add(top.subjectId());
        }

        CloseCache closeCache = new CloseCache();
        List<WindowView> windows = new ArrayList<>(WINDOW_PLUS_DAYS.size());
        for (int plusDays : WINDOW_PLUS_DAYS) {
            windows.add(windowOf(plusDays, topByDate, indexByDate, tradingDates, closeCache));
        }
        String asOf = tradingDates.isEmpty() ? null : tradingDates.get(tradingDates.size() - 1);
        log.info(
                "命中统计回算 rankDays={} tradingDates={} asOf={}",
                topByDate.size(),
                tradingDates.size(),
                asOf);
        return new HitStatsView(BASIS, asOf, DISCLAIMER, List.copyOf(windows));
    }

    /** 单窗口逐榜单日回算 + 池化聚合（样本日 < MIN_AGG_DAYS 或零有价样本 → INSUFFICIENT 如实）。 */
    private WindowView windowOf(
            int plusDays,
            Map<String, List<Long>> topByDate,
            Map<String, Integer> indexByDate,
            List<String> tradingDates,
            CloseCache closeCache) {
        List<DayStatView> days = new ArrayList<>();
        long totalUp = 0;
        long totalPriced = 0;
        List<Double> pooledPcts = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : topByDate.entrySet()) {
            String rankDate = entry.getKey();
            Integer baseIndex = indexByDate.get(rankDate);
            if (baseIndex == null) {
                continue; // 榜单日无基期价格（不在交易日序列）——不可算不计入
            }
            int targetIndex = baseIndex + plusDays;
            if (targetIndex >= tradingDates.size()) {
                continue; // 窗口目标日未到来（首跑三窗常态——不硬凑）
            }
            DayCompute day =
                    computeDay(
                            rankDate,
                            entry.getValue(),
                            closeCache.get(rankDate),
                            closeCache.get(tradingDates.get(targetIndex)));
            days.add(
                    new DayStatView(
                            rankDate,
                            day.topSize(),
                            day.priced(),
                            day.topSize() - day.priced(),
                            day.priced() == 0 ? null : round3(day.up() * 1.0 / day.priced()),
                            day.priced() == 0 ? null : median(day.pcts())));
            totalUp += day.up();
            totalPriced += day.priced();
            pooledPcts.addAll(day.pcts());
        }
        List<DayStatView> ascending = new ArrayList<>(days); // rank_date 降序入表 → 反转升序呈现
        Collections.reverse(ascending);
        boolean ok = ascending.size() >= MIN_AGG_DAYS && totalPriced > 0;
        return new WindowView(
                "T+" + plusDays,
                List.copyOf(ascending),
                new AggView(
                        ascending.size(),
                        ok ? "OK" : "INSUFFICIENT",
                        ok ? round3(totalUp * 1.0 / totalPriced) : null,
                        ok ? median(pooledPcts) : null));
    }

    /** 单榜单日有价样本回算：涨跌幅 = (目标 close − 基期 close)/基期 ×100；有价 = 两端 close 均非空且基期非零。 */
    private static DayCompute computeDay(
            String rankDate,
            List<Long> subjects,
            Map<Long, Double> base,
            Map<Long, Double> target) {
        long up = 0;
        List<Double> pcts = new ArrayList<>(subjects.size());
        for (Long subjectId : subjects) {
            Double baseClose = base.get(subjectId);
            Double targetClose = target.get(subjectId);
            if (baseClose == null || targetClose == null || baseClose == 0.0) {
                continue; // 停牌/无价剔除（计数由 topSize − priced 呈现，不隐藏）
            }
            double pct = (targetClose - baseClose) * 100.0 / baseClose;
            pcts.add(pct);
            if (pct > 0.0) {
                up++;
            }
        }
        return new DayCompute(subjects.size(), pcts.size(), up, pcts, rankDate);
    }

    /** 中位数（排序列插值：偶数取中间两均值——与对账 SQL 窗口函数同式）。 */
    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double median =
                n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return round2(median);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 单日回算中间态（rankDate 语义随行——日志与审计口径）。 */
    private record DayCompute(
            int topSize, int priced, long up, List<Double> pcts, String rankDate) {}

    /** 逐日收盘截面缓存（同日截面跨三窗复用——个人量级毫秒预算，方案 §5）。 */
    private final class CloseCache {
        private final Map<String, Map<Long, Double>> cache = new HashMap<>();

        Map<Long, Double> get(String snapshotDate) {
            return cache.computeIfAbsent(snapshotDate, marketRepository::findClosePrices);
        }
    }

    /** 统计视图（§4.2-③ 契约：basis 口径留档 + asOf + 免责 + 三窗）。 */
    public record HitStatsView(
            String basis, String asOf, String disclaimer, List<WindowView> windows) {}

    /** 单窗口视图（days 升序；agg 池化聚合）。 */
    public record WindowView(String window, List<DayStatView> days, AggView agg) {}

    /** 单榜单日统计（N/10 样本标注原料：pricedSamples/excluded）。 */
    public record DayStatView(
            String rankDate,
            int topSize,
            int pricedSamples,
            int excluded,
            Double upRatio,
            Double medianPctChg) {}

    /** 聚合态（OK / INSUFFICIENT——样本日 < 5 或零有价样本时比率如实空）。 */
    public record AggView(int days, String status, Double upRatio, Double medianPct) {}
}
