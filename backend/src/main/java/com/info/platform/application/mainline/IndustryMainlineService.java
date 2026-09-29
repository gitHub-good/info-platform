package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.mainline.IndustryMainlineSettings.LeaderParams;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.LeaderCalculator;
import com.info.platform.domain.mainline.LeaderCalculator.Candidate;
import com.info.platform.domain.mainline.LeaderCalculator.LeaderRow;
import com.info.platform.domain.mainline.MainlineCalculator;
import com.info.platform.domain.mainline.MainlineCalculator.CalculationInput;
import com.info.platform.domain.mainline.MainlineCalculator.DimDetail;
import com.info.platform.domain.mainline.MainlineCalculator.IndustryRow;
import com.info.platform.domain.mainline.MainlineCalculator.MainlineRow;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import com.info.platform.domain.mainline.MainlineCalculator.Result;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.EventWeightRow;
import com.info.platform.domain.mainline.MainlineRepository.FactorScoreRow;
import com.info.platform.domain.mainline.MainlineRepository.HeatTopDay;
import com.info.platform.domain.mainline.MainlineRepository.MainlineBatchRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import com.info.platform.domain.mainline.MainlineRepository.MarketQuoteRow;
import com.info.platform.domain.mainline.MainlineRepository.MemberRow;
import com.info.platform.domain.mainline.MainlineRepository.MentionCountRow;
import com.info.platform.domain.mainline.MainlineRepository.SubjectEventLinkRow;
import com.info.platform.domain.recommendation.IndustryDirectory;
import com.info.platform.domain.valuation.RiskFactor;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** 榜单口径日时区（幂等锚——Job/端点共用）。 */
    public static final ZoneId RANK_ZONE = ZoneId.of("Asia/Shanghai");

    /** 热度历史回看自然日窗（≥ 5 个交易日的日报留存裕量）。 */
    private static final int HEAT_HISTORY_LOOKBACK_DAYS = 14;

    /** 个股价格动量两窗（近 5 个行情快照日——market_daily_snapshot 口径，方案 §3.5）。 */
    private static final int PCT_WINDOW_QUOTES = 5;

    private final IndustryMarketSnapshotRepository marketSnapshotRepository;

    private final MainlineRepository mainlineRepository;

    private final HeatSnapshotRepository heatSnapshotRepository;

    private final IndustryMainlineSettings settings;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    private final AttentionProxyService attentionProxyService;

    public IndustryMainlineService(
            IndustryMarketSnapshotRepository marketSnapshotRepository,
            MainlineRepository mainlineRepository,
            HeatSnapshotRepository heatSnapshotRepository,
            IndustryMainlineSettings settings,
            Clock clock,
            ObjectMapper objectMapper,
            AttentionProxyService attentionProxyService) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.mainlineRepository = mainlineRepository;
        this.heatSnapshotRepository = heatSnapshotRepository;
        this.settings = settings;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.attentionProxyService = attentionProxyService;
    }

    /**
     * 计算一轮榜单（A 股兼容入口——Job/既有调用面零改动断言，方案 §4 C12）。
     *
     * @param rankDate 榜单口径日（Asia/Shanghai yyyy-MM-dd；同日重跑 version+1 追加）
     * @param manual true = 任务中心/端点手动触发（trigger_source=MANUAL 留痕）
     * @return 生成报告（行数 + detail——任务中心 lastRunDetail）
     */
    public GenerationReport compute(LocalDate rankDate, boolean manual) {
        return compute(rankDate, Market.A_SHARE, manual);
    }

    /**
     * 计算一轮榜单（Job tick 与手动重算共用入口；M29 T255 分市场——mainline-v1:m2 口径，全部输入按市场切横截面）。
     *
     * <p><b>m2 口径（方案 §6）</b>：三维权重与持续性硬门槛结构零变更，百分位市场内计算；价格维取 {@code
     * industry_market_snapshot(market)}（港美股 pct_d5 v1 留 NULL → 价格维降 day 单窗 + dimensionMissing
     * 留痕，M27 缺维中性化先例）；热度维取 {@code industry_heat_snapshot(market)}；事件维按源条目 {@code l1_market =
     * market} 分桶。 港美历史不足 persistMinDays → bootstrap 免门槛出榜（M27 先例）；龙头仅 A 股（W1——港美股 leaders 恒 {@code
     * []}）。
     *
     * @param market 市场口径（A_SHARE / HK / US）
     */
    public GenerationReport compute(LocalDate rankDate, Market market, boolean manual) {
        Params params = settings.mainlineParams();
        String date = rankDate.toString();
        String snapshotDate = marketSnapshotRepository.latestSnapshotDate(market).orElse(null);
        if (snapshotDate == null) {
            log.warn(
                    "主线计算跳过：该市场全库无行情快照（INDUSTRY_MARKET_SNAPSHOT 未跑过）rankDate={} market={}",
                    date,
                    market);
            return new GenerationReport(0, "no-snapshot（" + market + " 行情快照未就绪，本轮跳过）");
        }
        boolean degraded = snapshotDate.compareTo(date) < 0;
        List<MarketSnapshotRow> industryRows =
                marketSnapshotRepository.findIndustryRows(snapshotDate, market);
        if (industryRows.isEmpty()) {
            return new GenerationReport(0, "no-snapshot（" + market + " 行情快照无 INDUSTRY 行，本轮跳过）");
        }
        // 价格侧窗口：本表 distinct snapshot_date 倒取（含当日快照日，时间升序喂计算器）
        List<String> windowDates =
                new ArrayList<>(
                        marketSnapshotRepository.recentSnapshotDates(
                                params.persistWindowDays(), market));
        if (!windowDates.contains(snapshotDate)) {
            windowDates.add(0, snapshotDate);
        }
        java.util.Collections.reverse(windowDates);
        List<Map<String, Double>> dailyPctDay = loadDailyPctDay(market, windowDates, industryRows);

        // 热度侧：当日 H24/D7 现值 + 历史日报 heat_top（当日以现值计——日报次日 08:00 才生成；日报 v1 恒 A 股口径，
        // 港美股历史空窗 → 持续性按可得日计、不足 persistMinDays 走 bootstrap，方案 §6.2 拍板五）
        Map<String, IndustryHeatSnapshot> h24 = heatBoard(HeatWindow.H24, market);
        Map<String, IndustryHeatSnapshot> d7 = heatBoard(HeatWindow.D7, market);
        List<IndustryRow> current = new ArrayList<>(industryRows.size());
        Map<String, Double> eventWeighted = eventWeighted(windowDates, market);
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
        List<Map<String, Double>> dailyHeat = loadDailyHeat(windowDates, h24, market);

        Result result =
                MainlineCalculator.calculate(
                        params, new CalculationInput(List.copyOf(current), dailyPctDay, dailyHeat));

        // 龙头识别 + 主力徽章（T244）：仅 A 股生效（W1 港美股龙头 Won't——leaders 恒 []，detail 端点给 unavailableReason）
        LeaderOutcomes leaders = leadersFor(market, result.topRows(), rankDate);
        String computedAt = clock.instant().toString();
        String basis = basis(market, params, snapshotDate, windowDates);
        int version = mainlineRepository.maxVersion(date, market) + 1;
        List<MainlineRankRow> rankRows = new ArrayList<>(result.topRows().size());
        for (int i = 0; i < result.topRows().size(); i++) {
            MainlineRow row = result.topRows().get(i);
            rankRows.add(
                    new MainlineRankRow(
                            market,
                            date,
                            version,
                            i + 1,
                            row.industry(),
                            row.mainScore(),
                            dimDetailJson(row),
                            row.persistentDays(),
                            row.heatRank(),
                            row.divergence(),
                            leaders.jsonByIndustry().getOrDefault(row.industry(), "[]"),
                            basis,
                            computedAt));
        }
        MainlineBatchRow batch =
                new MainlineBatchRow(
                        market,
                        date,
                        version,
                        manual ? "MANUAL" : "DAILY",
                        snapshotDate,
                        funnelStats(result, industryRows.size(), leaders),
                        degraded,
                        degraded ? "SNAPSHOT_STALE" : null,
                        basis,
                        computedAt);
        mainlineRepository.insertVersion(batch, rankRows);
        String detail =
                "market="
                        + market
                        + " top="
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
                        + (result.bootstrap() ? " bootstrap=COLD_START" : "")
                        + " dimensionMissing="
                        + result.dimensionMissing();
        log.info("主线榜单落库完成 rankDate={} {}（Job 留痕摘要）", date, detail);
        return new GenerationReport(rankRows.size(), detail);
    }

    /** 价格侧窗口装载：窗口各交易日 industry→pct_day（时间升序、末位 = 当日；缺行日照常缺——持续性按可得日计）。 */
    private List<Map<String, Double>> loadDailyPctDay(
            Market market, List<String> windowDates, List<MarketSnapshotRow> todayRows) {
        Map<String, Map<String, Double>> byDate = new HashMap<>();
        for (HistoryPctDay row :
                marketSnapshotRepository.findIndustryPctDayForDates(windowDates, market)) {
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

    /**
     * 热度侧窗口装载：历史日报 heat_top + 当日 H24 现值（与价格侧按日对齐等长——缺数日空映射占位，末日 = 当日）。 日报 v1 恒 A 股口径（该表无 market
     * 维）——港美股历史空窗（仅当日现值），持续性按可得日计 → 不足 persistMinDays 由计算器 bootstrap 承接。
     */
    private List<Map<String, Double>> loadDailyHeat(
            List<String> windowDates, Map<String, IndustryHeatSnapshot> h24, Market market) {
        Map<String, Map<String, Double>> byDate = new HashMap<>();
        if (market == Market.A_SHARE) {
            for (HeatTopDay day :
                    mainlineRepository.findRecentHeatTop(HEAT_HISTORY_LOOKBACK_DAYS)) {
                Map<String, Double> scores = parseHeatTop(day.heatTopJson());
                if (!scores.isEmpty()) {
                    byDate.put(day.reportDate(), scores);
                }
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

    private Map<String, IndustryHeatSnapshot> heatBoard(HeatWindow window, Market market) {
        Map<String, IndustryHeatSnapshot> board = new HashMap<>();
        for (IndustryHeatSnapshot snapshot : heatSnapshotRepository.findBoard(window, market)) {
            board.put(snapshot.getIndustry(), snapshot);
        }
        return board;
    }

    /** 事件密度：窗口首日起 [from, to] 的加权计数（HIGH×2 / MEDIUM×1 / LOW×0，源条目 l1_market = market 分桶，SQL 可复算）。 */
    private Map<String, Double> eventWeighted(List<String> windowDates, Market market) {
        String from = windowDates.get(0);
        String to = windowDates.get(windowDates.size() - 1);
        Map<String, Double> weighted = new HashMap<>();
        for (EventWeightRow row : mainlineRepository.sumEventWeightByIndustry(from, to, market)) {
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

    /** funnel_stats JSON（§4.1 契约：industries/persistPass/topN/dimensionMissing/excluded）。 */
    private String funnelStats(Result result, int industries, LeaderOutcomes leaders) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("industries", industries);
        doc.put("persistPass", result.gatePassed());
        doc.put("topN", result.topRows().size());
        doc.put("bootstrap", result.bootstrap());
        ObjectNode missing = doc.putObject("dimensionMissing");
        result.dimensionMissing().forEach(missing::put);
        ObjectNode excluded = doc.putObject("excluded");
        excluded.put("st", leaders.excludedSt());
        ObjectNode memberCoverage = doc.putObject("memberCoverage");
        leaders.memberCoverage().forEach(memberCoverage::put);
        return doc.toString();
    }

    /** 龙头产出（行业 → leaders JSON + 排除/覆盖率留痕）。 */
    private record LeaderOutcomes(
            Map<String, String> jsonByIndustry,
            int excludedSt,
            Map<String, Integer> memberCoverage) {

        static final LeaderOutcomes EMPTY = new LeaderOutcomes(Map.of(), 0, Map.of());
    }

    /** 逐 Top 行业识别龙头（成员装载 → ST 排除留痕 → 三维计算 → 徽章内嵌 → JSON 组装）；港美股恒空产出（W1）。 */
    private LeaderOutcomes leadersFor(
            Market market, List<MainlineRow> topRows, LocalDate rankDate) {
        if (topRows.isEmpty() || market != Market.A_SHARE) {
            // W1：港美股龙头分析暂未支持（依赖基本面因子体系）——leaders 恒 []，detail 端点 leadersAvailable=false + reason 占位
            return LeaderOutcomes.EMPTY;
        }
        LeaderParams leaderParams = settings.leaderParams();
        List<MemberRow> members = mainlineRepository.findActiveMembers();
        Map<String, List<MemberRow>> membersByIndustry = new HashMap<>();
        int unmapped = 0;
        for (MemberRow member : members) {
            String sw =
                    IndustryCategory.isSwIndustry(member.industry())
                            ? member.industry()
                            : IndustryDirectory.swPrimaryOf(member.industry());
            if (sw == null) {
                unmapped++;
                continue;
            }
            membersByIndustry.computeIfAbsent(sw, key -> new ArrayList<>()).add(member);
        }
        if (unmapped > 0) {
            log.debug("龙头成员装载：行业原文未收录跳过 {} 行（swPrimaryOf 安全侧）", unmapped);
        }
        // V 维原料（最新因子快照，一次性装载）
        String factorDate = mainlineRepository.latestFactorSnapshotDate().orElse(null);
        Map<Long, FactorScoreRow> factorScores = new HashMap<>();
        if (factorDate != null) {
            for (FactorScoreRow row : mainlineRepository.findFactorScores(factorDate)) {
                factorScores.put(row.subjectId(), row);
            }
        }
        // Q 维两窗原料（近 5 个行情快照日，一次性装载）
        List<String> quoteDates =
                new ArrayList<>(mainlineRepository.recentMarketQuoteDates(PCT_WINDOW_QUOTES));
        java.util.Collections.reverse(quoteDates);
        Map<String, Map<String, Double>> quotesByDate = new HashMap<>();
        for (MarketQuoteRow row : mainlineRepository.findMarketPctChangeForDates(quoteDates)) {
            quotesByDate
                    .computeIfAbsent(row.code(), key -> new HashMap<>())
                    .put(row.snapshotDate(), row.pctChange());
        }

        Map<String, String> jsonByIndustry = new LinkedHashMap<>();
        Map<String, Integer> coverage = new LinkedHashMap<>();
        int excludedSt = 0;
        List<String> leaderCodes = new ArrayList<>();
        Map<String, List<LeaderRow>> rowsByIndustry = new LinkedHashMap<>();
        for (MainlineRow top : topRows) {
            List<MemberRow> industryMembers =
                    membersByIndustry.getOrDefault(top.industry(), List.of());
            List<Candidate> candidates = new ArrayList<>(industryMembers.size());
            Map<String, Integer> mentions = mentionsOf(top.industry(), rankDate, leaderParams);
            Map<String, List<SubjectEventLinkRow>> eventLinks =
                    eventLinksOf(top.industry(), rankDate);
            for (MemberRow member : industryMembers) {
                if (RiskFactor.isStName(member.name())) {
                    excludedSt++; // excluded.st 留痕（funnel_stats——REQ 故事 3 场景）
                    continue;
                }
                List<SubjectEventLinkRow> links = eventLinks.getOrDefault(member.code(), List.of());
                double eventWeighted = 0d;
                int riskEvents = 0;
                for (SubjectEventLinkRow link : links) {
                    eventWeighted +=
                            "HIGH".equals(link.importance())
                                    ? 2d
                                    : "MEDIUM".equals(link.importance()) ? 1d : 0d;
                    if ("BEARISH".equals(link.direction())) {
                        riskEvents++;
                    }
                }
                FactorScoreRow score = factorScores.get(member.subjectId());
                Map<String, Double> quotes = quotesByDate.getOrDefault(member.code(), Map.of());
                Double pctDay =
                        quoteDates.isEmpty()
                                ? null
                                : quotes.get(quoteDates.get(quoteDates.size() - 1));
                candidates.add(
                        new Candidate(
                                member.subjectId(),
                                member.code(),
                                member.name(),
                                mentions.getOrDefault(member.code(), 0),
                                eventWeighted,
                                links.size(),
                                riskEvents,
                                score == null ? null : score.totalScore(),
                                score == null ? null : score.dataFlagsJson(),
                                pctDay,
                                compoundPct(quotes, quoteDates)));
            }
            coverage.put(top.industry(), industryMembers.size());
            LeaderCalculator.Result leaderResult =
                    LeaderCalculator.calculate(leaderParamsToCalc(leaderParams), candidates);
            rowsByIndustry.put(top.industry(), leaderResult.leaders());
            leaderResult.leaders().forEach(leader -> leaderCodes.add(leader.subjectCode()));
        }
        // 主力徽章统一补齐（≤15 只 × 2 报表——集中一轮，页间礼貌间隔在 Proxy 内）
        List<com.fasterxml.jackson.databind.node.ObjectNode> badges =
                attentionProxyService.badgesFor(leaderCodes);
        Map<String, com.fasterxml.jackson.databind.node.ObjectNode> badgeByCode = new HashMap<>();
        for (int i = 0; i < leaderCodes.size() && i < badges.size(); i++) {
            badgeByCode.put(leaderCodes.get(i), badges.get(i));
        }
        rowsByIndustry.forEach(
                (industry, rows) ->
                        jsonByIndustry.put(
                                industry,
                                leadersJson(
                                        rows,
                                        eventLinksOf(industry, rankDate),
                                        badgeByCode,
                                        factorDate)));
        return new LeaderOutcomes(jsonByIndustry, excludedSt, coverage);
    }

    /** 龙头参数 → 计算器参数（同键直映射）。 */
    private static LeaderCalculator.Params leaderParamsToCalc(LeaderParams params) {
        return new LeaderCalculator.Params(
                params.wa(),
                params.wv(),
                params.wq(),
                params.mentionDays(),
                params.topN(),
                params.qDay(),
                params.qD5());
    }

    /** 提及计数（mentionDays 自然日窗，Asia/Shanghai——created_at ISO 文本下界）。 */
    private Map<String, Integer> mentionsOf(
            String industry, LocalDate rankDate, LeaderParams params) {
        String fromIso = rankDate.minusDays(params.mentionDays()).toString() + "T00:00:00";
        String toIso = rankDate.plusDays(1).toString() + "T00:00:00";
        Map<String, Integer> mentions = new HashMap<>();
        for (MentionCountRow row :
                mainlineRepository.countMentionsByIndustry(industry, fromIso, toIso)) {
            mentions.put(row.code(), row.mentions());
        }
        return mentions;
    }

    /** 标的事件关联（mentionDays 窗，event_date 口径）。 */
    private Map<String, List<SubjectEventLinkRow>> eventLinksOf(
            String industry, LocalDate rankDate) {
        LeaderParams params = settings.leaderParams();
        Map<String, List<SubjectEventLinkRow>> byCode = new HashMap<>();
        for (SubjectEventLinkRow link :
                mainlineRepository.findSubjectEventLinks(
                        industry,
                        rankDate.minusDays(params.mentionDays()).toString(),
                        rankDate.toString())) {
            byCode.computeIfAbsent(link.code(), key -> new ArrayList<>()).add(link);
        }
        return byCode;
    }

    /** 个股 pct_d5 复利（近 5 行情日 pct_change——market_daily_snapshot 库内自算，任一日缺值返 null）。 */
    private static Double compoundPct(Map<String, Double> quotesByDate, List<String> quoteDates) {
        if (quoteDates.isEmpty()) {
            return null;
        }
        double factor = 1d;
        for (String date : quoteDates) {
            Double pct = quotesByDate.get(date);
            if (pct == null) {
                return null;
            }
            factor *= 1d + pct / 100d;
        }
        return (factor - 1d) * 100d;
    }

    /** leaders JSON（§4.4.3 契约：rank/rankLabel/三维分解/basis 回溯/attention 徽章/disclaimer）。 */
    private String leadersJson(
            List<LeaderRow> rows,
            Map<String, List<SubjectEventLinkRow>> eventLinks,
            Map<String, com.fasterxml.jackson.databind.node.ObjectNode> badgeByCode,
            String factorDate) {
        com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
        for (LeaderRow row : rows) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("rank", row.rank());
            node.put("rankLabel", rankLabel(row.rank()));
            node.put("subjectId", row.subjectId());
            node.put("subjectCode", row.subjectCode());
            node.put("subjectName", row.subjectName());
            node.put("score", row.score());
            ObjectNode dim = node.putObject("dim");
            dim.set("attention", attentionNode(row));
            dim.set("value", valueNode(row, factorDate));
            dim.set("price", priceNode(row));
            ObjectNode basis = node.putObject("basis");
            List<SubjectEventLinkRow> links = eventLinks.getOrDefault(row.subjectCode(), List.of());
            List<Long> eventIds =
                    links.stream()
                            .filter(
                                    link ->
                                            "HIGH".equals(link.importance())
                                                    || "MEDIUM".equals(link.importance()))
                            .map(SubjectEventLinkRow::eventId)
                            .limit(2)
                            .toList();
            com.fasterxml.jackson.databind.node.ArrayNode eventIdArray = basis.putArray("eventIds");
            eventIds.forEach(eventIdArray::add);
            basis.put("factorSnapshotDate", factorDate == null ? "" : factorDate);
            basis.put("riskEvents", row.attention() == null ? 0 : riskEventsOf(links));
            basis.putNull("divergenceNote");
            com.fasterxml.jackson.databind.node.ObjectNode badge =
                    badgeByCode.get(row.subjectCode());
            node.set("attention", badge == null ? objectMapper.createObjectNode() : badge);
            node.put("disclaimer", "关注度排名，非投资建议，不构成买卖依据");
            array.add(node);
        }
        return array.toString();
    }

    private static int riskEventsOf(List<SubjectEventLinkRow> links) {
        return (int) links.stream().filter(link -> "BEARISH".equals(link.direction())).count();
    }

    /** 龙次名（1/2/3 → 龙一/二/三；4+ 序数兜底——topN 可配至 5）。 */
    private static String rankLabel(int rank) {
        return switch (rank) {
            case 1 -> "龙一";
            case 2 -> "龙二";
            case 3 -> "龙三";
            default -> "第" + rank;
        };
    }

    /** dim.attention（§4.4.3：score/mentions/eventCount/eventWeighted）。 */
    private ObjectNode attentionNode(LeaderRow row) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("score", row.attention().score());
        node.put("mentions", row.mentions());
        node.put("eventCount", row.eventCount());
        node.put("eventWeighted", row.eventWeighted());
        return node;
    }

    /** dim.value（§4.4.3：score/totalScore/snapshotDate/dataFlags）。 */
    private ObjectNode valueNode(LeaderRow row, String factorDate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("score", row.value().score());
        if (row.value().raw() == null) {
            node.putNull("totalScore");
        } else {
            node.put("totalScore", row.value().raw());
        }
        node.put("snapshotDate", factorDate == null ? "" : factorDate);
        node.set(
                "dataFlags",
                row.dataFlagsJson() == null
                        ? objectMapper.createArrayNode()
                        : parseJsonArray(row.dataFlagsJson()));
        return node;
    }

    /** dim.price（§4.4.3：score/pctDay/pctD5/flag）。 */
    private ObjectNode priceNode(LeaderRow row) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("score", row.price().score());
        if (row.pctDay() == null) {
            node.putNull("pctDay");
        } else {
            node.put("pctDay", row.pctDay());
        }
        if (row.pctD5() == null) {
            node.putNull("pctD5");
        } else {
            node.put("pctD5", row.pctD5());
        }
        node.put("flag", row.price().flag() == null ? "" : row.price().flag());
        return node;
    }

    /** data_flags JSON 数组透传（解析失败落空数组——展示面不阻塞）。 */
    private com.fasterxml.jackson.databind.node.ArrayNode parseJsonArray(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = objectMapper.readTree(json);
            if (parsed.isArray()) {
                return (com.fasterxml.jackson.databind.node.ArrayNode) parsed;
            }
        } catch (Exception e) {
            log.warn("data_flags 解析失败（落空数组）: {}", e.getMessage());
        }
        return objectMapper.createArrayNode();
    }

    /**
     * basis 口径串（§3.4 契约：权重/阈值/输入指纹全量拼入——复算对账锚；M29 §6：A 股 mainline-v1 / 港美股 mainline-v1:m2 前缀区分）。
     */
    private String basis(
            Market market, Params params, String snapshotDate, List<String> windowDates) {
        String version = market == Market.A_SHARE ? "mainline-v1" : "mainline-v1:m2";
        return version
                + ":wp="
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
