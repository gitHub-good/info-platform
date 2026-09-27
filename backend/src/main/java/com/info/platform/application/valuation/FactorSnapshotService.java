package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.recommendation.IndustryDirectory;
import com.info.platform.domain.valuation.CatalystFactor;
import com.info.platform.domain.valuation.ConductionFactor;
import com.info.platform.domain.valuation.FactorEntry;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.EventRef;
import com.info.platform.domain.valuation.FactorSnapshotRepository.IndustryMemberRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.NewsLinkRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.FundamentalFactor;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.IndustryAssociator;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import com.info.platform.domain.valuation.IndustryAssociator.EventLink;
import com.info.platform.domain.valuation.IndustryAssociator.MemberLink;
import com.info.platform.domain.valuation.IndustryAssociator.NewsLink;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import com.info.platform.domain.valuation.RiskFactor;
import com.info.platform.domain.valuation.ScoreComposer;
import com.info.platform.domain.valuation.ValuationEvent;
import com.info.platform.domain.valuation.ValuationFactor;
import com.info.platform.domain.valuation.ValuationParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        // 阶段 1~2 输入投影 + 逐标的计算（与 recomputeIncremental 完全同源，§3.2 零漂移构造保证）
        Projection projection = projection(snapshotDate, params, subjects);
        List<FactorSnapshotRow> rows = new ArrayList<>(projection.subjects().size());
        Map<String, Long> flagCounts = new LinkedHashMap<>();
        for (String flag : CANONICAL_FLAGS) {
            flagCounts.put(flag, 0L);
        }
        for (SubjectRef subject : projection.subjects()) {
            RowWithFlags computed = rowOf(projection, subject, snapshotDate, params);
            rows.add(computed.row());
            computed.flags().forEach(flag -> flagCounts.merge(flag, 1L, Long::sum));
        }

        // 阶段 3：批 UPSERT（500/批，increment_at 显式置 NULL 复位——盘后全量清除增量标注，V33）+ 报告
        int upserted = upsertInBatches(rows, null);
        SnapshotReport report =
                reportOf(
                        subjects.size(), rows, projection.marketMap().size(), upserted, flagCounts);
        log.info("因子快照完成 date={} {}（basis={}）", dateText, report.detail(), params.basis());
        return report;
    }

    /**
     * 增量重算受影响标的的当日快照行（M22 T190，方案 §3.2 / ADR-0061 裁决 2）：阶段 1 投影与 {@link #snapshotAll} <b>完全同源</b>
     * （同一 W2 窗事件/回联/热度/成员投影查询、同一 marketMap 截面），阶段 2 循环裁剪到目标标的，阶段 3 一次批 UPSERT 且 {@code
     * increment_at} 置本轮时刻——零漂移由构造保证（同投影 + 同 {@code rowOf} 纯函数 + {@code settings.params()}
     * 热读同一权重版本）。
     *
     * <p>不拉行情（行情面属 17:30 全量职责——增量读既有当日截面，快照日守卫语义不变）；不触碰集外标的（写入范围即受影响集）。
     *
     * @param snapshotDate 快照口径日（当日行覆盖锚）
     * @param subjectIds 受影响标的集（池内 id；空集零写入）
     */
    public IncrementalReport recomputeIncremental(
            LocalDate snapshotDate, Collection<Long> subjectIds) {
        ValuationParams params = settings.params();
        Set<Long> targets = subjectIds == null ? Set.of() : Set.copyOf(subjectIds);
        if (targets.isEmpty()) {
            return new IncrementalReport(0, null);
        }
        Projection projection = projection(snapshotDate, params, repository.findActiveSubjects());
        List<FactorSnapshotRow> rows =
                new ArrayList<>(Math.min(targets.size(), projection.subjects().size()));
        for (SubjectRef subject : projection.subjects()) {
            if (targets.contains(subject.id())) {
                rows.add(rowOf(projection, subject, snapshotDate, params).row());
            }
        }
        String incrementAt = clock.instant().toString();
        int upserted = upsertInBatches(rows, incrementAt);
        log.info(
                "增量重算完成 date={} rows={} upserted={}（basis={}）",
                snapshotDate,
                rows.size(),
                upserted,
                params.basis());
        return new IncrementalReport(upserted, incrementAt);
    }

    /** 增量重算报告（upsertedRows 写入行数 + incrementAt 增量覆盖时刻——留痕 snapshot_at 与双层时间戳依据）。 */
    public record IncrementalReport(int upsertedRows, String incrementAtIso) {}

    // ---- 阶段 1：输入投影（全量/增量同源——零漂移的分叉点不存在，§3.2） ----

    /** 一轮输入投影（全部载入内存——§5 容量实测：W2 窗数千行级；M21 六输入扩位 + 行业成员投影）。 */
    private record Projection(
            List<SubjectRef> subjects,
            Map<String, List<ValuationEvent>> eventsByCode,
            Map<String, List<Association>> associations,
            List<HeatRow> heatRows,
            Map<Long, MarketDailyRow> marketMap,
            List<Double> peCrossSection,
            List<Double> pbCrossSection) {}

    private Projection projection(
            LocalDate snapshotDate, ValuationParams params, List<SubjectRef> subjects) {
        String dateText = snapshotDate.toString();
        List<EventRef> events =
                repository.findEventsInWindow(
                        snapshotDate.minusDays(params.assocWindowDays() - 1L).toString(), dateText);
        List<NewsLinkRow> newsLinks =
                repository.findMatchedNewsInWindow(
                        windowStartIso(snapshotDate, params.assocWindowDays()),
                        dayEndIso(snapshotDate));
        List<HeatRow> heatRows = repository.findH24Heat();
        List<MemberLink> memberLinks = memberLinks(repository.findIndustryMembers());
        Map<Long, MarketDailyRow> marketMap = marketRepository.findByDate(dateText);

        Map<String, List<ValuationEvent>> eventsByCode = eventsByCode(events);
        Map<String, List<Association>> associations =
                IndustryAssociator.associate(
                        eventLinks(events),
                        newsLinks(newsLinks),
                        memberLinks,
                        snapshotDate,
                        params.assocWindowDays());
        return new Projection(
                subjects,
                eventsByCode,
                associations,
                heatRows,
                marketMap,
                positiveValues(marketMap, MarketDailyRow::peTtm),
                positiveValues(marketMap, MarketDailyRow::pb));
    }

    // ---- 阶段 2：单标的行组装 ----

    private RowWithFlags rowOf(
            Projection projection,
            SubjectRef subject,
            LocalDate snapshotDate,
            ValuationParams params) {
        return rowOf(
                subject,
                snapshotDate,
                params,
                projection.eventsByCode().getOrDefault(subject.code(), List.of()),
                projection.associations().getOrDefault(subject.code(), List.of()),
                projection.heatRows(),
                projection.marketMap().get(subject.id()),
                projection.peCrossSection(),
                projection.pbCrossSection());
    }

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
        double f1r = round1(f1.score());
        double f2r = round1(f2.score());
        double f3r = round1(f3.score());
        double f4r = round1(f4.score());
        double f5r = round1(f5.score());
        // T171 ScoreComposer：总分 = Σ w×F / Σw（展示总分 = 展示分维加权均值，审计自洽）；标签三阈值可配
        ScoreComposer.Composed composed = ScoreComposer.compose(f1r, f2r, f3r, f4r, f5r, params);
        FactorSnapshotRow row =
                new FactorSnapshotRow(
                        subject.id(),
                        snapshotDate.toString(),
                        f1r,
                        f2r,
                        f3r,
                        f4r,
                        f5r,
                        composed.totalScore(),
                        composed.breakthrough(),
                        detail,
                        writeJson(flags),
                        params.basis(),
                        clock.instant().toString(),
                        lastEventDateText(f1, f3, f4));
        return new RowWithFlags(row, flags);
    }

    /**
     * 最近事件日（M21 §4.1.6）：max(catalyst/fundamental/risk 三维护据事件的 eventDate)—— 无事件返回 null（粗筛四键 NULL
     * 视最旧）。
     */
    private static String lastEventDateText(
            CatalystFactor.Result f1, FundamentalFactor.Result f3, RiskFactor.Result f4) {
        LocalDate latest = null;
        for (List<FactorEntry> entries : List.of(f1.entries(), f3.entries(), f4.entries())) {
            for (FactorEntry entry : entries) {
                if (latest == null || entry.eventDate().isAfter(latest)) {
                    latest = entry.eventDate();
                }
            }
        }
        return latest == null ? null : latest.toString();
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
        return upsertInBatches(rows, null);
    }

    /** 批 UPSERT（500/批；incrementAtIso 空 = 全量路径——{@code increment_at} 显式置 NULL 复位；非空 = 增量覆盖时刻）。 */
    private int upsertInBatches(List<FactorSnapshotRow> rows, String incrementAtIso) {
        int upserted = 0;
        for (int i = 0; i < rows.size(); i += UPSERT_BATCH_SIZE) {
            List<FactorSnapshotRow> batch =
                    rows.subList(i, Math.min(i + UPSERT_BATCH_SIZE, rows.size()));
            upserted +=
                    incrementAtIso == null
                            ? repository.upsertAll(batch)
                            : repository.upsertAllIncremental(batch, incrementAtIso);
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

    /** 事件投影 → 路 A 输入（public：T202 单标的读口同源复用——同一转换点非第二套，ADR-0062 裁决三）。 */
    public static List<EventLink> eventLinks(List<EventRef> events) {
        return events.stream()
                .map(
                        ref ->
                                new EventLink(
                                        ref.subjectCodes(),
                                        ref.affectedIndustries(),
                                        ref.event().eventDate()))
                .toList();
    }

    /** 资讯投影 → 路 B 输入（public：T202 单标的读口同源复用——同一转换点非第二套，ADR-0062 裁决三）。 */
    public static List<NewsLink> newsLinks(List<NewsLinkRow> rows) {
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

    /**
     * 行业成员投影 → 路 C 输入（§4.1.3/4.1.4）：industry 原文经 {@code IndustryDirectory.swPrimaryOf} 映射，
     * 未收录板块过滤不出行 （安全侧失败——少关联不误关联；映射缺漏计数观察留 JobRunStats，T183 阶段 0 detail 接线）。
     *
     * <p>public：M22 增量受影响集与全量同源复用（ADR-0061 裁决 2）；V2.3-M23 T202 SubjectIndustryAssociationReader
     * 第三消费面（详情分区关联集同源锚定——同一转换点非第二套，ADR-0062 裁决三）。
     */
    public static List<MemberLink> memberLinks(List<IndustryMemberRow> rows) {
        List<MemberLink> links = new ArrayList<>();
        for (IndustryMemberRow row : rows) {
            String swIndustry = IndustryDirectory.swPrimaryOf(row.industry());
            if (swIndustry == null) {
                continue;
            }
            links.add(new MemberLink(row.code(), swIndustry));
        }
        return links;
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
