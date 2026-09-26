package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.valuation.CatalystFactor;
import com.info.platform.domain.valuation.ConductionFactor;
import com.info.platform.domain.valuation.FactorEntry;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.EventRef;
import com.info.platform.domain.valuation.FactorSnapshotRepository.NewsLinkRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.FundamentalFactor;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.IndustryAssociator;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import com.info.platform.domain.valuation.IndustryAssociator.EventLink;
import com.info.platform.domain.valuation.IndustryAssociator.NewsLink;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import com.info.platform.domain.valuation.RiskFactor;
import com.info.platform.domain.valuation.ValuationEvent;
import com.info.platform.domain.valuation.ValuationFactor;
import com.info.platform.domain.valuation.ValuationParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 因子快照编排服务（应用层，M20 方案 §4.6 四阶段 / §4.4 幂等口径）：盘后一轮把五路存量原料喂给五个领域纯函数， 按（标的，快照日）幂等落 {@code
 * subject_factor_snapshot}。
 *
 * <ol>
 *   <li>阶段 0 行情拉取：{@link MarketDataSnapshotService}（整段失败不中止——四维 Must 链零依赖行情）；
 *   <li>阶段 1 输入投影：W2 窗事件/回联行 + H24 热度 + 标的名录 + 当日行情快照，一次性载入内存；
 *   <li>阶段 2 逐标的计算：IndustryAssociator 派生关联 → 五因子（纯函数锚定 snapshot_date）→ flags/明细 JSON；
 *   <li>阶段 3 批 UPSERT：500 行/批，报告对账计数（coverage 端点同口径）。
 * </ol>
 *
 * <p>总分合成与「有突破」标签由 {@link ScoreComposer}（T171）承担：归一合成 + 三阈值判定，weight_basis 按当轮参数落 指纹串。幂等口径 = 五输入 +
 * 参数不变 → 输出不变（§4.4）；computed_at 为计算时刻，豁免逐字段比较。
 */
@Service
public class FactorSnapshotService {

    /** 快照口径时区（event_date/snapshot_date 同口径，V23 惯例）。 */
    static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 批 UPSERT 行数（方案 §4.6 阶段 3）。 */
    static final int UPSERT_BATCH_SIZE = 500;

    /** flags 常驻键（coverage flagCounts 同序）。 */
    static final String FLAG_NO_MARKET_DATA = "NO_MARKET_DATA";

    static final String FLAG_NO_ASSOC_INDUSTRY = "NO_ASSOC_INDUSTRY";
    static final String FLAG_NO_VALUATION_DATA = "NO_VALUATION_DATA";
    static final String FLAG_ST_RISK = "ST_RISK";

    static final List<String> CANONICAL_FLAGS =
            List.of(
                    FLAG_NO_MARKET_DATA,
                    FLAG_NO_ASSOC_INDUSTRY,
                    FLAG_NO_VALUATION_DATA,
                    FLAG_ST_RISK);

    private static final Logger log = LoggerFactory.getLogger(FactorSnapshotService.class);

    private final FactorSnapshotRepository repository;
    private final MarketDailySnapshotRepository marketRepository;
    private final MarketDataSnapshotService marketService;
    private final ValuationSettings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FactorSnapshotService(
            FactorSnapshotRepository repository,
            MarketDailySnapshotRepository marketRepository,
            MarketDataSnapshotService marketService,
            ValuationSettings settings,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.marketRepository = marketRepository;
        this.marketService = marketService;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 跑一轮全市场因子快照（定时与手动触发共用入口）。
     *
     * @param snapshotDate 快照口径日（幂等锚——窗口与衰减全部锚定该日）
     */
    public SnapshotReport snapshotAll(LocalDate snapshotDate) {
        ValuationParams params = settings.params(); // 热读（ADR-0017：保存即对下一轮生效）
        String dateText = snapshotDate.toString();

        // 阶段 0：行情拉取（整段失败不中止——四维 Must 链零依赖行情，§4.6）
        List<SubjectRef> subjects = repository.findActiveSubjects();
        marketService.refresh(snapshotDate, subjects);

        // 阶段 1：输入投影（全部载入内存——§5 容量实测：W2 窗数千行级）
        List<EventRef> events =
                repository.findEventsInWindow(
                        snapshotDate.minusDays(params.assocWindowDays() - 1L).toString(), dateText);
        List<NewsLinkRow> newsLinks =
                repository.findMatchedNewsInWindow(
                        windowStartIso(snapshotDate, params.assocWindowDays()),
                        dayEndIso(snapshotDate));
        List<HeatRow> heatRows = repository.findH24Heat();
        Map<Long, MarketDailyRow> marketMap = marketRepository.findByDate(dateText);

        // 阶段 2：逐标的计算
        Map<String, List<ValuationEvent>> eventsByCode = eventsByCode(events);
        Map<String, List<Association>> associations =
                IndustryAssociator.associate(
                        eventLinks(events),
                        newsLinks(newsLinks),
                        snapshotDate,
                        params.assocWindowDays());
        List<Double> peCrossSection = positiveValues(marketMap, MarketDailyRow::peTtm);
        List<Double> pbCrossSection = positiveValues(marketMap, MarketDailyRow::pb);

        List<FactorSnapshotRow> rows = new ArrayList<>(subjects.size());
        Map<String, Long> flagCounts = new LinkedHashMap<>();
        for (String flag : CANONICAL_FLAGS) {
            flagCounts.put(flag, 0L);
        }
        for (SubjectRef subject : subjects) {
            RowWithFlags computed =
                    rowOf(
                            subject,
                            snapshotDate,
                            params,
                            eventsByCode.getOrDefault(subject.code(), List.of()),
                            associations.getOrDefault(subject.code(), List.of()),
                            heatRows,
                            marketMap.get(subject.id()),
                            peCrossSection,
                            pbCrossSection);
            rows.add(computed.row());
            computed.flags().forEach(flag -> flagCounts.merge(flag, 1L, Long::sum));
        }

        // 阶段 3：批 UPSERT（500/批）+ 报告（coverage 端点同口径）
        int upserted = upsertInBatches(rows);
        SnapshotReport report =
                reportOf(subjects.size(), rows, marketMap.size(), upserted, flagCounts);
        log.info("因子快照完成 date={} {}（basis={}）", dateText, report.detail(), params.basis());
        return report;
    }

    // ---- 阶段 2：单标的行组装 ----

    private RowWithFlags rowOf(
            SubjectRef subject,
            LocalDate snapshotDate,
            ValuationParams params,
            List<ValuationEvent> subjectEvents,
            List<Association> subjectAssociations,
            List<HeatRow> heatRows,
            MarketDailyRow marketRow,
            List<Double> peCrossSection,
            List<Double> pbCrossSection) {
        CatalystFactor.Result f1 = CatalystFactor.compute(subjectEvents, snapshotDate, params);
        ConductionFactor.Result f2 =
                ConductionFactor.compute(subjectAssociations, heatRows, params);
        FundamentalFactor.Result f3 =
                FundamentalFactor.compute(subjectEvents, snapshotDate, params);
        RiskFactor.Result f4 =
                RiskFactor.compute(subjectEvents, subject.name(), snapshotDate, params);
        ValuationFactor.Result f5 =
                ValuationFactor.compute(
                        marketRow == null ? null : marketRow.peTtm(),
                        marketRow == null ? null : marketRow.pb(),
                        peCrossSection,
                        pbCrossSection);

        List<String> flags = flagsOf(subjectAssociations, marketRow, f5, f4);
        String detail = detailJson(f1, f2, f3, f4, f5);
        FactorSnapshotRow row =
                new FactorSnapshotRow(
                        subject.id(),
                        snapshotDate.toString(),
                        round1(f1.score()),
                        round1(f2.score()),
                        round1(f3.score()),
                        round1(f4.score()),
                        round1(f5.score()),
                        0.0, // T171 ScoreComposer 接管（归一合成 + 三阈值标签）
                        false,
                        detail,
                        writeJson(flags),
                        params.basis(),
                        clock.instant().toString());
        return new RowWithFlags(row, flags);
    }

    /** 行 + flags 原值（报告计数免二次解析 JSON）。 */
    private record RowWithFlags(FactorSnapshotRow row, List<String> flags) {}

    private static List<String> flagsOf(
            List<Association> associations,
            MarketDailyRow marketRow,
            ValuationFactor.Result f5,
            RiskFactor.Result f4) {
        List<String> flags = new ArrayList<>(4);
        if (marketRow == null) {
            flags.add(FLAG_NO_MARKET_DATA);
        }
        if (associations.isEmpty()) {
            flags.add(FLAG_NO_ASSOC_INDUSTRY);
        }
        if (marketRow != null && f5.neutral()) {
            flags.add(FLAG_NO_VALUATION_DATA); // 无行情行只记 NO_MARKET_DATA（不双重标注）
        }
        if (f4.stFlag()) {
            flags.add(FLAG_ST_RISK);
        }
        return flags;
    }

    /** §4.5 factor_detail 契约（五维明细；序列化失败 fail-fast——明细是 trace-v1 依据面不可静默缺失）。 */
    private String detailJson(
            CatalystFactor.Result f1,
            ConductionFactor.Result f2,
            FundamentalFactor.Result f3,
            RiskFactor.Result f4,
            ValuationFactor.Result f5) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("catalyst", Map.of("raw", f1.raw(), "entries", entryMaps(f1.entries())));
        detail.put("conduction", Map.of("assoc", f2.assoc()));
        detail.put("fundamental", Map.of("raw", f3.raw(), "entries", entryMaps(f3.entries())));
        detail.put(
                "risk",
                Map.of(
                        "stFlag", f4.stFlag(),
                        "eventPenalty", f4.eventPenalty(),
                        "entries", entryMaps(f4.entries())));
        Map<String, Object> valuation = new LinkedHashMap<>();
        valuation.put("basis", f5.basis());
        valuation.put("pe", f5.pe());
        valuation.put("pct", f5.pct());
        valuation.put("pb", f5.pb());
        detail.put("valuation", valuation);
        return writeJson(detail);
    }

    /** FactorEntry → 纯标量 Map（eventDate 落 yyyy-MM-dd 文本，§4.5 示例；枚举落 name）。 */
    private static List<Map<String, Object>> entryMaps(List<FactorEntry> entries) {
        List<Map<String, Object>> mapped = new ArrayList<>(entries.size());
        for (FactorEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", entry.eventId());
            item.put("summary", entry.summary());
            item.put("eventDate", entry.eventDate().toString());
            item.put("direction", entry.direction().name());
            item.put("importance", entry.importance().name());
            item.put("coef", entry.coef());
            item.put("decay", entry.decay());
            mapped.add(item);
        }
        return mapped;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("因子明细 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    // ---- 阶段 3：批 UPSERT 与报告 ----

    private int upsertInBatches(List<FactorSnapshotRow> rows) {
        int upserted = 0;
        for (int i = 0; i < rows.size(); i += UPSERT_BATCH_SIZE) {
            upserted +=
                    repository.upsertAll(
                            rows.subList(i, Math.min(i + UPSERT_BATCH_SIZE, rows.size())));
        }
        return upserted;
    }

    private SnapshotReport reportOf(
            int activeSubjects,
            List<FactorSnapshotRow> rows,
            int marketRows,
            int upserted,
            Map<String, Long> flagCounts) {
        double coverage =
                activeSubjects == 0
                        ? 0.0
                        : round1(100.0 * Math.min(rows.size(), activeSubjects) / activeSubjects);
        return new SnapshotReport(
                activeSubjects, rows.size(), marketRows, coverage, flagCounts, upserted);
    }

    // ---- 阶段 1 辅助：投影转换与窗口界 ----

    private static Map<String, List<ValuationEvent>> eventsByCode(List<EventRef> events) {
        Map<String, List<ValuationEvent>> byCode = new HashMap<>();
        for (EventRef ref : events) {
            for (String code : ref.subjectCodes()) {
                byCode.computeIfAbsent(code, unused -> new ArrayList<>()).add(ref.event());
            }
        }
        return byCode;
    }

    private static List<EventLink> eventLinks(List<EventRef> events) {
        return events.stream()
                .map(
                        ref ->
                                new EventLink(
                                        ref.subjectCodes(),
                                        ref.affectedIndustries(),
                                        ref.event().eventDate()))
                .toList();
    }

    private static List<NewsLink> newsLinks(List<NewsLinkRow> rows) {
        return rows.stream()
                .map(
                        row ->
                                new NewsLink(
                                        row.subjectCodes(),
                                        row.mainCategory(),
                                        row.subIndustry(),
                                        row.publishedDate()))
                .toList();
    }

    private static List<Double> positiveValues(
            Map<Long, MarketDailyRow> marketMap,
            java.util.function.Function<MarketDailyRow, Double> extractor) {
        return marketMap.values().stream()
                .map(extractor)
                .filter(value -> value != null && value > 0)
                .toList();
    }

    /** W2 窗下界（ISO，上海日界 00:00 含）。 */
    private static String windowStartIso(LocalDate snapshotDate, int windowDays) {
        return snapshotDate
                .minusDays(windowDays - 1L)
                .atStartOfDay(SNAPSHOT_ZONE)
                .toInstant()
                .toString();
    }

    /** 快照日日界上界（ISO，次日 00:00 不含）。 */
    private static String dayEndIso(LocalDate snapshotDate) {
        return snapshotDate.plusDays(1).atStartOfDay(SNAPSHOT_ZONE).toInstant().toString();
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 快照轮报告（JobRunStats 留痕 + coverage 端点同口径；processedCount = snapshotRows）。 */
    public record SnapshotReport(
            int activeSubjects,
            int snapshotRows,
            int marketDataRows,
            double coverageRate,
            Map<String, Long> flagCounts,
            int upsertedRows) {

        /** 常用构造（upserted 缺省 = rows）。 */
        public SnapshotReport(
                int activeSubjects,
                int snapshotRows,
                int marketDataRows,
                double coverageRate,
                Map<String, Long> flagCounts) {
            this(
                    activeSubjects,
                    snapshotRows,
                    marketDataRows,
                    coverageRate,
                    flagCounts,
                    snapshotRows);
        }

        /** 留痕明细段串（「subjects=…;rows=…;coverage=…;market=…;flags=…」）。 */
        public String detail() {
            StringBuilder text =
                    new StringBuilder()
                            .append("subjects=")
                            .append(activeSubjects)
                            .append(";rows=")
                            .append(snapshotRows)
                            .append(";coverage=")
                            .append(coverageRate)
                            .append(";market=")
                            .append(marketDataRows)
                            .append(";flags=");
            if (flagCounts == null || flagCounts.isEmpty()) {
                return text.append("-").toString();
            }
            List<String> parts = new ArrayList<>();
            flagCounts.forEach((flag, count) -> parts.add(flag + "=" + count));
            return text.append(String.join(",", parts)).toString();
        }
    }
}
