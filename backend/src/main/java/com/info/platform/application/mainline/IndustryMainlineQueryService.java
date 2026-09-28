package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.MainlineRepository;
import com.info.platform.domain.mainline.MainlineRepository.MainlineRankRow;
import com.info.platform.domain.mainline.MainlineRepository.MainlineVersion;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 主线/热力图读取服务（M27 T244，方案 §4.5 三读端点）：heat-map（31 行业 + meta stale 标注）/ mainline（版本化榜单回退读）/
 * detail（下钻——通道 A 板块明细 / 通道 B 成分股涨跌 + 领涨股两形态）。错误码 30093/30094/30095。
 */
@Service
public class IndustryMainlineQueryService {

    private static final Logger log = LoggerFactory.getLogger(IndustryMainlineQueryService.class);

    /** stale 判定倍数（now − quote_time > 2×采集间隔，方案 §4.2.3）。 */
    static final int STALE_INTERVAL_MULTIPLIER = 2;

    /** 采集间隔缺省（job 键缺失时的回退 30min）。 */
    private static final long DEFAULT_INTERVAL_MILLIS = 1_800_000L;

    /** 通道 B 下钻成分股涨跌 Top N（方案 §4.5 detail 契约）。 */
    private static final int CONSTITUENT_TOP_N = 10;

    /** 通道 B 下钻成分股跌幅 Bottom N。 */
    private static final int CONSTITUENT_BOTTOM_N = 5;

    private final IndustryMarketSnapshotRepository marketSnapshotRepository;

    private final MainlineRepository mainlineRepository;

    private final RuntimeConfigService configService;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    public IndustryMainlineQueryService(
            IndustryMarketSnapshotRepository marketSnapshotRepository,
            MainlineRepository mainlineRepository,
            RuntimeConfigService configService,
            Clock clock,
            ObjectMapper objectMapper) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.mainlineRepository = mainlineRepository;
        this.configService = configService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 31 行业热力数据（date 缺省当日，无当日行回退最近有行日；全库无行 30093——空态由前端呈现）。 */
    public HeatMapView heatMap(String date) {
        String snapshotDate = resolveSnapshotDate(date);
        List<MarketSnapshotRow> rows = marketSnapshotRepository.findIndustryRows(snapshotDate);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INDUSTRY_MARKET_SNAPSHOT_EMPTY, "行业行情快照无任何数据");
        }
        String source = rows.get(0).source();
        String quoteTime = rows.get(0).quoteTime();
        List<IndustryCell> industries = new ArrayList<>(rows.size());
        for (MarketSnapshotRow row : rows) {
            industries.add(
                    new IndustryCell(
                            row.industry(),
                            row.pctDay(),
                            row.pctD5(),
                            row.upCount(),
                            row.downCount(),
                            row.mainNetFlow(),
                            row.totalMv(),
                            row.aggMethod(),
                            parseJson(row.leaderStockJson())));
        }
        return new HeatMapView(
                snapshotDate, source, quoteTime, stale(quoteTime), List.copyOf(industries));
    }

    /** 主线榜单（date/version 缺省最新有榜日最大版本；非交易日回退最近榜日；全库无榜 30094）。 */
    public MainlineView mainline(String date, String version) {
        String rankDate = date == null || date.isBlank() ? null : requireDate(date);
        Integer parsedVersion = null;
        if (version != null && !version.isBlank()) {
            try {
                parsedVersion = Integer.parseInt(version);
            } catch (NumberFormatException e) {
                throw new BusinessException(
                        ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID, "version 须为正整数");
            }
        }
        Optional<MainlineVersion> found = Optional.empty();
        if (rankDate != null && parsedVersion != null) {
            found = mainlineRepository.find(rankDate, parsedVersion);
            if (found.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID,
                        "该日期+版本不存在: " + rankDate + " v" + parsedVersion);
            }
        } else if (rankDate != null) {
            found = mainlineRepository.findLatest(rankDate);
        }
        if (found.isEmpty()) {
            found = mainlineRepository.findLatestAnyDate();
        }
        MainlineVersion mainlineVersion =
                found.orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INDUSTRY_MAINLINE_NOT_FOUND, "全库无主线榜单"));
        List<MainlineItemView> items = new ArrayList<>(mainlineVersion.items().size());
        for (MainlineRankRow row : mainlineVersion.items()) {
            items.add(
                    new MainlineItemView(
                            row.rankNo(),
                            row.industry(),
                            row.mainScore(),
                            parseJson(row.dimDetailJson()),
                            row.persistentDays(),
                            row.heatRank(),
                            row.divergence(),
                            parseJson(row.leadersJson()),
                            row.basis(),
                            row.computedAt()));
        }
        return new MainlineView(
                mainlineVersion.batch().rankDate(),
                mainlineVersion.batch().version(),
                mainlineVersion.batch().triggerSource(),
                mainlineVersion.batch().snapshotDate(),
                mainlineVersion.batch().degraded(),
                mainlineVersion.batch().degradedReason(),
                mainlineVersion.batch().basis(),
                mainlineVersion.batch().createdAt(),
                List.copyOf(items));
    }

    /** 行业下钻（当日行 source 分形态：A→板块明细；B→成分股涨跌 Top10/Bottom5 + 领涨股 + 龙头 + 成员统计）。 */
    public DetailView detail(String industry) {
        if (!IndustryCategory.isSwIndustry(industry)) {
            throw new BusinessException(
                    ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID, "行业参数非申万 31 枚举: " + industry);
        }
        String snapshotDate =
                marketSnapshotRepository
                        .latestSnapshotDate()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_MARKET_SNAPSHOT_EMPTY,
                                                "行业行情快照无任何数据"));
        List<MarketSnapshotRow> industryRows =
                marketSnapshotRepository.findIndustryRows(snapshotDate);
        MarketSnapshotRow row =
                industryRows.stream()
                        .filter(r -> r.industry().equals(industry))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID,
                                                "该行业无快照行: " + industry));
        boolean channelA = "eastmoney-push2".equals(row.source());
        List<BoardCell> boards = List.of();
        List<ConstituentCell> constituents = List.of();
        if (channelA) {
            boards =
                    marketSnapshotRepository
                            .findBoardRowsOfIndustry(snapshotDate, industry)
                            .stream()
                            .map(
                                    board ->
                                            new BoardCell(
                                                    board.dimName(),
                                                    board.pctDay(),
                                                    board.upCount(),
                                                    board.downCount(),
                                                    board.mainNetFlow(),
                                                    board.totalMv()))
                            .toList();
        } else {
            constituents = constituentsOf(industry);
        }
        List<MainlineRepository.MemberRow> members = mainlineRepository.findActiveMembers();
        long memberCount =
                members.stream()
                        .filter(
                                member ->
                                        industry.equals(
                                                        com.info.platform.domain.recommendation
                                                                .IndustryDirectory.swPrimaryOf(
                                                                member.industry()))
                                                || industry.equals(member.industry()))
                        .count();
        // 该行业最新榜单行的龙头卡（无榜单/未入榜 → 空数组——区块独立三态）
        JsonNode leaders = objectMapper.createArrayNode();
        Optional<MainlineVersion> latest = mainlineRepository.findLatestAnyDate();
        if (latest.isPresent()) {
            leaders =
                    latest.orElseThrow().items().stream()
                            .filter(item -> item.industry().equals(industry))
                            .findFirst()
                            .map(item -> parseJson(item.leadersJson()))
                            .orElse(leaders);
        }
        return new DetailView(
                industry,
                snapshotDate,
                row.source(),
                row.quoteTime(),
                stale(row.quoteTime()),
                row.pctDay(),
                row.pctD5(),
                row.upCount(),
                row.downCount(),
                row.mainNetFlow(),
                row.totalMv(),
                row.aggMethod(),
                parseJson(row.leaderStockJson()),
                boards,
                constituents,
                leaders,
                memberCount);
    }

    /** 通道 B 成分股涨跌（成员集 + market_daily_snapshot 最新日 pct_change——Top10/Bottom5）。 */
    private List<ConstituentCell> constituentsOf(String industry) {
        List<String> quoteDates = mainlineRepository.recentMarketQuoteDates(1);
        if (quoteDates.isEmpty()) {
            return List.of();
        }
        String latest = quoteDates.get(0);
        java.util.Map<String, String> namesByCode = new java.util.HashMap<>();
        for (MainlineRepository.MemberRow member : mainlineRepository.findActiveMembers()) {
            if (industry.equals(member.industry())
                    || industry.equals(
                            com.info.platform.domain.recommendation.IndustryDirectory.swPrimaryOf(
                                    member.industry()))) {
                namesByCode.put(member.code(), member.name());
            }
        }
        List<MainlineRepository.MarketQuoteRow> quotes =
                mainlineRepository.findMarketPctChangeForDates(List.of(latest)).stream()
                        .filter(quote -> namesByCode.containsKey(quote.code()))
                        .filter(quote -> quote.pctChange() != null)
                        .sorted(
                                java.util.Comparator.comparing(
                                                MainlineRepository.MarketQuoteRow::pctChange)
                                        .reversed())
                        .toList();
        if (quotes.isEmpty()) {
            return List.of();
        }
        List<MainlineRepository.MarketQuoteRow> top =
                quotes.subList(0, Math.min(CONSTITUENT_TOP_N, quotes.size()));
        List<MainlineRepository.MarketQuoteRow> bottom =
                quotes.subList(Math.max(0, quotes.size() - CONSTITUENT_BOTTOM_N), quotes.size());
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        top.forEach(quote -> codes.add(quote.code()));
        bottom.forEach(quote -> codes.add(quote.code()));
        java.util.Map<String, Double> pctByCode = new java.util.HashMap<>();
        quotes.forEach(quote -> pctByCode.put(quote.code(), quote.pctChange()));
        return codes.stream()
                .map(
                        code ->
                                new ConstituentCell(
                                        code,
                                        namesByCode.getOrDefault(code, code),
                                        pctByCode.get(code)))
                .toList();
    }

    /** 快照日解析（缺省 = 当日；无当日行回退最近有行日；非法日期 30095）。 */
    private String resolveSnapshotDate(String date) {
        String today =
                clock.instant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate()
                        .toString();
        String wanted = date == null || date.isBlank() ? today : requireDate(date);
        return marketSnapshotRepository.recentSnapshotDates(Integer.MAX_VALUE).stream()
                .filter(d -> d.compareTo(wanted) <= 0)
                .findFirst()
                .orElse(today);
    }

    private static String requireDate(String raw) {
        try {
            return java.time.LocalDate.parse(raw).toString();
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID, "date 须为 yyyy-MM-dd: " + raw);
        }
    }

    /** stale 判定：now − quote_time > 2×采集间隔（间隔取 job 键热值，缺省 30min）。 */
    private boolean stale(String quoteTime) {
        if (quoteTime == null || quoteTime.isBlank()) {
            return true;
        }
        try {
            OffsetDateTime quoted = OffsetDateTime.parse(quoteTime);
            long interval =
                    configService
                            .read("job.INDUSTRY_MARKET_SNAPSHOT")
                            .map(entry -> entry.document().path("intervalMillis").asLong(0L))
                            .filter(millis -> millis > 0)
                            .orElse(DEFAULT_INTERVAL_MILLIS);
            return clock.instant()
                    .isAfter(quoted.toInstant().plusMillis(STALE_INTERVAL_MULTIPLIER * interval));
        } catch (DateTimeParseException e) {
            log.warn("quote_time 解析失败（按 stale 处理）: {}", quoteTime);
            return true;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("mainline JSON 列解析失败（透传 null）: {}", e.getMessage());
            return null;
        }
    }

    /** heat-map 响应（meta + 31 行业格）。 */
    public record HeatMapView(
            String snapshotDate,
            String source,
            String quoteTime,
            boolean stale,
            List<IndustryCell> industries) {}

    /** 热力图单行业格。 */
    public record IndustryCell(
            String industry,
            Double pctDay,
            Double pctD5,
            Integer upCount,
            Integer downCount,
            Double mainNetFlow,
            Double totalMv,
            String aggMethod,
            JsonNode leaderStock) {}

    /** 榜单响应（batch 元信息 + 行集）。 */
    public record MainlineView(
            String rankDate,
            int version,
            String triggerSource,
            String snapshotDate,
            boolean degraded,
            String degradedReason,
            String basis,
            String computedAt,
            List<MainlineItemView> items) {}

    /** 榜单单行。 */
    public record MainlineItemView(
            int rankNo,
            String industry,
            double mainScore,
            JsonNode dimDetail,
            int persistentDays,
            Integer heatRank,
            String divergence,
            JsonNode leaders,
            String basis,
            String computedAt) {}

    /** 下钻响应（两形态共用：boards（通道 A）/ constituents（通道 B））。 */
    public record DetailView(
            String industry,
            String snapshotDate,
            String source,
            String quoteTime,
            boolean stale,
            Double pctDay,
            Double pctD5,
            Integer upCount,
            Integer downCount,
            Double mainNetFlow,
            Double totalMv,
            String aggMethod,
            JsonNode leaderStock,
            List<BoardCell> boards,
            List<ConstituentCell> constituents,
            JsonNode leaders,
            long memberCount) {}

    /** 板块明细格（通道 A）。 */
    public record BoardCell(
            String boardName,
            Double pctDay,
            Integer upCount,
            Integer downCount,
            Double mainNetFlow,
            Double totalMv) {}

    /** 成分股格（通道 B）。 */
    public record ConstituentCell(String code, String name, Double pctChange) {}
}
