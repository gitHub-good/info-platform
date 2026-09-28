package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.MainlineCalculator;
import com.info.platform.domain.mainline.MainlineCalculator.CalculationInput;
import com.info.platform.domain.mainline.MainlineCalculator.DimDetail;
import com.info.platform.domain.mainline.MainlineCalculator.IndustryRow;
import com.info.platform.domain.mainline.MainlineCalculator.MainlineRow;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import com.info.platform.domain.mainline.MainlineCalculator.Result;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.EventWeightRow;
import com.info.platform.domain.mainline.MainlineRepository.HeatTopDay;
import com.info.platform.domain.mainline.MainlineRepository.MainlineBatchRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 主线计算编排 mainline-v1（M27 T243，方案 §4.3 + ADR-0063 裁决 4）：输入装载（行情快照 INDUSTRY 行 + 热度双窗快照 + 事件密度 SQL）→
 * {@link MainlineCalculator} 纯函数三维合成 + 持续性硬门槛 → 版本化落库（同日重算 version+1；纯函数零漂移）。
 *
 * <p><b>快照守卫</b>（方案 §4.3.1）：当日无 INDUSTRY 行 → 消费最近有行日 + {@code degraded=1,
 * reason=SNAPSHOT_STALE}（榜单日期 标注照常）；全库无行情 → no-op 留痕（不上空榜）。热度侧历史 = 日报 heat_top 全量留存；当日以 H24
 * 现值快照计（日报次日 08:00 才出， 18:30 计算时点当日日报未生成，方案 §4.3.2）。
 */
@Service
public class IndustryMainlineService {

    private static final Logger log = LoggerFactory.getLogger(IndustryMainlineService.class);

    /** 榜单口径日时区（幂等锚）。 */
    static final ZoneId RANK_ZONE = ZoneId.of("Asia/Shanghai");

    /** 热度历史回看自然日窗（≥ 5 个交易日的日报留存裕量）。 */
    private static final int HEAT_HISTORY_LOOKBACK_DAYS = 14;

    private final IndustryMarketSnapshotRepository marketSnapshotRepository;

    private final MainlineRepository mainlineRepository;

    private final HeatSnapshotRepository heatSnapshotRepository;

    private final IndustryMainlineSettings settings;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    public IndustryMainlineService(
            IndustryMarketSnapshotRepository marketSnapshotRepository,
            MainlineRepository mainlineRepository,
            HeatSnapshotRepository heatSnapshotRepository,
            IndustryMainlineSettings settings,
            Clock clock,
            ObjectMapper objectMapper) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.mainlineRepository = mainlineRepository;
        this.heatSnapshotRepository = heatSnapshotRepository;
        this.settings = settings;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算一轮榜单（Job tick 与手动重算共用入口）。
     *
     * @param rankDate 榜单口径日（Asia/Shanghai yyyy-MM-dd；同日重跑 version+1 追加）
     * @param manual true = 任务中心/端点手动触发（trigger_source=MANUAL 留痕）
     * @return 生成报告（行数 + detail——任务中心 lastRunDetail）
     */
    public GenerationReport compute(LocalDate rankDate, boolean manual) {
        Params params = settings.mainlineParams();
        String date = rankDate.toString();
        String snapshotDate = marketSnapshotRepository.latestSnapshotDate().orElse(null);
        if (snapshotDate == null) {
            log.warn("主线计算跳过：全库无行情快照（INDUSTRY_MARKET_SNAPSHOT 未跑过）rankDate={}", date);
            return new GenerationReport(0, "no-snapshot（行情快照未就绪，本轮跳过）");
        }
        boolean degraded = snapshotDate.compareTo(date) < 0;
        List<MarketSnapshotRow> industryRows =
                marketSnapshotRepository.findIndustryRows(snapshotDate);
        if (industryRows.isEmpty()) {
            return new GenerationReport(0, "no-snapshot（行情快照无 INDUSTRY 行，本轮跳过）");
        }
        // 价格侧窗口：本表 distinct snapshot_date 倒取（含当日快照日，时间升序喂计算器）
        List<String> windowDates =
                new ArrayList<>(
                        marketSnapshotRepository.recentSnapshotDates(params.persistWindowDays()));
        if (!windowDates.contains(snapshotDate)) {
            windowDates.add(0, snapshotDate);
        }
        java.util.Collections.reverse(windowDates);
        List<Map<String, Double>> dailyPctDay = loadDailyPctDay(windowDates, industryRows);

        // 热度侧：当日 H24/D7 现值 + 历史日报 heat_top（当日以现值计——日报次日 08:00 才生成）
        Map<String, IndustryHeatSnapshot> h24 = heatBoard(HeatWindow.H24);
        Map<String, IndustryHeatSnapshot> d7 = heatBoard(HeatWindow.D7);
        List<IndustryRow> current = new ArrayList<>(industryRows.size());
        Map<String, Double> eventWeighted = eventWeighted(windowDates);
        for (MarketSnapshotRow row : industryRows) {
            IndustryHeatSnapshot h24Row = h24.get(row.industry());
            IndustryHeatSnapshot d7Row = d7.get(row.industry());
            current.add(
                    new IndustryRow(
                            row.industry(),
                            row.pctDay(),
                            row.pctD5(),
                            h24Row == null ? null : h24Row.getHeatScore(),
                            d7Row == null ? null : d7Row.getHeatScore(),
                            h24Row == null ? null : h24Row.getDeltaPct(),
                            eventWeighted.getOrDefault(row.industry(), 0d)));
        }
        List<Map<String, Double>> dailyHeat = loadDailyHeat(windowDates, h24);

        Result result =
                MainlineCalculator.calculate(
                        params, new CalculationInput(List.copyOf(current), dailyPctDay, dailyHeat));

        String computedAt = clock.instant().toString();
        String basis = basis(params, snapshotDate, windowDates);
        int version = mainlineRepository.maxVersion(date) + 1;
        List<MainlineRankRow> rankRows = new ArrayList<>(result.topRows().size());
        for (int i = 0; i < result.topRows().size(); i++) {
            MainlineRow row = result.topRows().get(i);
            rankRows.add(
                    new MainlineRankRow(
                            date,
                            version,
                            i + 1,
                            row.industry(),
                            row.mainScore(),
                            dimDetailJson(row),
                            row.persistentDays(),
                            row.heatRank(),
                            row.divergence(),
                            "[]",
                            basis,
                            computedAt));
        }
        MainlineBatchRow batch =
                new MainlineBatchRow(
                        date,
                        version,
                        manual ? "MANUAL" : "DAILY",
                        snapshotDate,
                        funnelStats(result, industryRows.size()),
                        degraded,
                        degraded ? "SNAPSHOT_STALE" : null,
                        basis,
                        computedAt);
        mainlineRepository.insertVersion(batch, rankRows);
        String detail =
                "top="
                        + rankRows.size()
                        + " version="
                        + version
                        + " gatePassed="
                        + result.gatePassed()
                        + "/"
                        + industryRows.size()
                        + " snapshot="
                        + snapshotDate
                        + (degraded ? " degraded=SNAPSHOT_STALE" : "")
                        + " dimensionMissing="
                        + result.dimensionMissing();
        log.info("主线榜单落库完成 rankDate={} {}（Job 留痕摘要）", date, detail);
        return new GenerationReport(rankRows.size(), detail);
    }

    /** 价格侧窗口装载：窗口各交易日 industry→pct_day（时间升序、末位 = 当日；缺行日照常缺——持续性按可得日计）。 */
    private List<Map<String, Double>> loadDailyPctDay(
            List<String> windowDates, List<MarketSnapshotRow> todayRows) {
        Map<String, Map<String, Double>> byDate = new HashMap<>();
        for (HistoryPctDay row : marketSnapshotRepository.findIndustryPctDayForDates(windowDates)) {
            byDate.computeIfAbsent(row.snapshotDate(), key -> new HashMap<>())
                    .put(row.industry(), row.pctDay());
        }
        // 当日（快照日）行以现值覆盖（升序末位）
        Map<String, Double> today = new HashMap<>();
        todayRows.forEach(row -> today.put(row.industry(), row.pctDay()));
        byDate.put(windowDates.get(windowDates.size() - 1), today);
        List<Map<String, Double>> daily = new ArrayList<>(windowDates.size());
        for (String date : windowDates) {
            daily.add(byDate.getOrDefault(date, Map.of()));
        }
        return daily;
    }

    /** 热度侧窗口装载：历史日报 heat_top + 当日 H24 现值（与价格侧按日对齐等长——缺数日空映射占位，末日 = 当日）。 */
    private List<Map<String, Double>> loadDailyHeat(
            List<String> windowDates, Map<String, IndustryHeatSnapshot> h24) {
        Map<String, Map<String, Double>> byDate = new HashMap<>();
        for (HeatTopDay day : mainlineRepository.findRecentHeatTop(HEAT_HISTORY_LOOKBACK_DAYS)) {
            Map<String, Double> scores = parseHeatTop(day.heatTopJson());
            if (!scores.isEmpty()) {
                byDate.put(day.reportDate(), scores);
            }
        }
        Map<String, Double> today = new HashMap<>();
        h24.forEach((industry, snapshot) -> today.put(industry, snapshot.getHeatScore()));
        byDate.put(windowDates.get(windowDates.size() - 1), today);
        List<Map<String, Double>> daily = new ArrayList<>(windowDates.size());
        for (String date : windowDates) {
            daily.add(byDate.getOrDefault(date, Map.of()));
        }
        return daily;
    }

    private Map<String, IndustryHeatSnapshot> heatBoard(HeatWindow window) {
        Map<String, IndustryHeatSnapshot> board = new HashMap<>();
        for (IndustryHeatSnapshot snapshot : heatSnapshotRepository.findBoard(window)) {
            board.put(snapshot.getIndustry(), snapshot);
        }
        return board;
    }

    /** 事件密度：窗口首日起 [from, to] 的加权计数（HIGH×2 / MEDIUM×1 / LOW×0，SQL 可复算）。 */
    private Map<String, Double> eventWeighted(List<String> windowDates) {
        String from = windowDates.get(0);
        String to = windowDates.get(windowDates.size() - 1);
        Map<String, Double> weighted = new HashMap<>();
        for (EventWeightRow row : mainlineRepository.sumEventWeightByIndustry(from, to)) {
            weighted.put(row.industry(), row.weightedCount());
        }
        return weighted;
    }

    /** heat_top JSON 解析（DailyReportService.heatTopJson 契约：industry/heatScore/...）。 */
    private Map<String, Double> parseHeatTop(String json) {
        Map<String, Double> scores = new HashMap<>();
        if (json == null || json.isBlank()) {
            return scores;
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode item : array) {
                    String industry = item.path("industry").asText(null);
                    if (industry != null && item.path("heatScore").isNumber()) {
                        scores.put(industry, item.path("heatScore").asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("heat_top JSON 解析失败（该日热度历史缺数）: {}", e.getMessage());
        }
        return scores;
    }

    /** dim_detail JSON（§4.1 契约：{"price":{rank,score,raw},...}——raw null 透传）。 */
    private String dimDetailJson(MainlineRow row) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.set("price", dimNode(row.price()));
        doc.set("heat", dimNode(row.heat()));
        doc.set("event", dimNode(row.event()));
        return doc.toString();
    }

    private ObjectNode dimNode(DimDetail detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("rank", detail.rank());
        node.put("score", detail.score());
        if (detail.raw() == null) {
            node.putNull("raw");
        } else {
            node.put("raw", detail.raw());
        }
        return node;
    }

    /** funnel_stats JSON（§4.1 契约：industries/persistPass/topN/dimensionMissing）。 */
    private String funnelStats(Result result, int industries) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("industries", industries);
        doc.put("persistPass", result.gatePassed());
        doc.put("topN", result.topRows().size());
        ObjectNode missing = doc.putObject("dimensionMissing");
        result.dimensionMissing().forEach(missing::put);
        return doc.toString();
    }

    /** basis 口径串（§3.4 契约：权重/阈值/输入指纹全量拼入——复算对账锚）。 */
    private String basis(Params params, String snapshotDate, List<String> windowDates) {
        return "mainline-v1:wp="
                + params.wp()
                + ",wh="
                + params.wh()
                + ",we="
                + params.we()
                + ";pw="
                + params.priceWinDay()
                + "/"
                + params.priceWinD5()
                + ";hw="
                + params.heatH24()
                + "/"
                + params.heatD7()
                + "/"
                + params.heatDelta()
                + ";persist>="
                + params.persistMinDays()
                + "/"
                + params.persistWindowDays()
                + ";topN="
                + params.topN()
                + ";d5=self|tencent"
                + ";input=IMS:"
                + snapshotDate
                + "+EV:"
                + windowDates.get(0)
                + "~"
                + windowDates.get(windowDates.size() - 1);
    }

    /** 生成报告（JobRunStats 上报口径）。 */
    public record GenerationReport(int topSize, String detail) {}
}
