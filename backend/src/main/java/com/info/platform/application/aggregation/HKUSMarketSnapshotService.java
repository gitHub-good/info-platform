package com.info.platform.application.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 港美股行情快照编排（M29 T252，方案 §4 C10 / ADR-0064 裁决 1/3/4）——一职三责：
 *
 * <ol>
 *   <li><b>个股批量快照</b>：{@code subject_pool(HK+US)} → 腾讯主链（50/请求分块 500ms 礼貌间隔）→ 整轮失败切新浪备链
 *       （轮级降级非逐标的，{@code source} 落行留痕）→ {@code market_daily_snapshot} UPSERT（含 V37 新列 market_cap
 *       亿原币 / currency 原币口径，拍板六）；
 *   <li><b>行业就地聚合</b>（板块通道两源皆死，Spike-E §3.2）：快照行 × {@code subject.industry} 市值加权 → 覆盖市值 &lt; 80%（缺失
 *       &gt; 20%）回退等权（agg_method 留痕）→ {@code industry_market_snapshot(market='HK'/'US')}；
 *   <li><b>美股代表集市值收敛</b>：market_cap ≥ 阈值（runtime_config {@code subject.sync.us-mv-min-usd}，缺省 20 亿
 *       USD）保持/复活 status=1，其余 status=0 留池可查；每日首轮宽取（含 status=0 行）维护升降级（ADR-0064 裁决 3）。
 * </ol>
 *
 * <p><b>港股无市值收敛</b>（方案 §1.1 裁决③「港股全量」——无港币阈值；任务提示「港币同理」与方案冲突，以方案为准已回注）。 UNKNOWN 行业成员不进聚合行（行业行 =
 * 各市场枚举集，未分类无行业语义，Spike-E §3.3 裁量）；pct_d5 不在本轮计算（分市场 交易日窗查询属 T255 读面改造，v1 留 NULL）；main_net_flow /
 * leader_stock 港美股恒 NULL（A 股通道专有 / 龙头 Won't W1， 如实缺省不造假）。
 *
 * <p><b>降级语义</b>：主链整轮失败（异常或非空池零行）当轮切备链；双链全败该市场 no-op（不动旧快照，WARN 留痕）——A 股 M20/M27 链路结构性零触碰。收盘后轮次
 * UPSERT 同值幂等定格（UNIQUE(subject_id, snapshot_date)）。
 *
 * <p>美股盘中实时性复核（Spike-E §7 条款 2，方案 §10 R3）：实测时闭市，两源时间戳均停在美东收盘——<b>待盘中复核</b> （交易日美东 09:30~16:00 实测
 * {@code usAAPL} f30 推进 + 新浪同刻对价，结论回注方案复核栏）。
 */
@Service
public class HKUSMarketSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(HKUSMarketSnapshotService.class);

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();

    /** 快照口径日时区（幂等锚，与 M20/M27 同款）。 */
    static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 单请求标的数（= 客户端块上限，一次取数调用即一次 HTTP 请求）。 */
    static final int REQUEST_BATCH_SIZE = 50;

    /** 相邻请求间隔（ms）——方案 §8 成本护栏：50/请求分块 + 500ms 礼貌间隔。 */
    static final long INTER_REQUEST_INTERVAL_MILLIS = 500L;

    /** 美股市值收敛阈值缺省（20 亿 USD，ADR-0064 裁决 3 罗素 3000 量级）。 */
    static final double DEFAULT_US_MV_MIN_USD = 2_000_000_000d;

    /** 美股收敛阈值 runtime_config 键（方案 §3.5：代码 seed，SubjectSyncRuntimeConfigSeeder 播种）。 */
    static final String US_MV_MIN_CONFIG_KEY = "subject.sync.us-mv-min-usd";

    /** 阈值合法下界（1 亿 USD——防误配清空代表集）。 */
    static final double US_MV_MIN_FLOOR_USD = 100_000_000d;

    /** 市值加权覆盖不足回退等权的阈值（覆盖市值 &lt; 80% = 缺失 &gt; 20%，ADR-0064 裁决 1）。 */
    static final double MV_COVERAGE_FALLBACK_RATIO = 0.80d;

    /** 亿原币 → 元换算（total_mv 单位对齐 A 股板块行，V37 ② 注释）。 */
    private static final BigDecimal YI_TO_YUAN = BigDecimal.valueOf(100_000_000L);

    /** 港美股聚合行 source 口径（V37 ② 列注释枚举值）。 */
    static final String AGG_SOURCE = "hkus-aggregate";

    /**
     * 主链 bean 名（{@code infrastructure.aggregation.TencentHkusQuoteSource#BEAN_NAME}，字符串引用防跨层依赖）。
     */
    private static final String PRIMARY_BEAN = "tencentHkusQuoteSource";

    /** 备链 bean 名（{@code infrastructure.aggregation.SinaHkusQuoteSource#BEAN_NAME}）。 */
    private static final String BACKUP_BEAN = "sinaHkusQuoteSource";

    private final HkusQuoteSource primarySource;

    private final HkusQuoteSource backupSource;

    private final SubjectRepository subjectRepository;

    private final MarketDailySnapshotRepository dailySnapshotRepository;

    private final IndustryMarketSnapshotRepository industrySnapshotRepository;

    private final RuntimeConfigService runtimeConfigService;

    private final Clock clock;

    /** 美股宽取轮锚（每日首轮含 status=0 行维护升降级；进程内状态，重启后首轮重新宽取——幂等无害）。 */
    private volatile LocalDate lastUsWideRoundDate;

    public HKUSMarketSnapshotService(
            @Qualifier(PRIMARY_BEAN) HkusQuoteSource primarySource,
            @Qualifier(BACKUP_BEAN) HkusQuoteSource backupSource,
            SubjectRepository subjectRepository,
            MarketDailySnapshotRepository dailySnapshotRepository,
            IndustryMarketSnapshotRepository industrySnapshotRepository,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.primarySource = primarySource;
        this.backupSource = backupSource;
        this.subjectRepository = subjectRepository;
        this.dailySnapshotRepository = dailySnapshotRepository;
        this.industrySnapshotRepository = industrySnapshotRepository;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    /**
     * 采集一轮（Job tick 与手动触发共用入口；快照口径日 = Asia/Shanghai 当日；两市场独立容错——单市场失败不阻断另一市场）。
     *
     * @return 轮次报告（行数 + 收敛统计——任务中心 lastRunDetail）
     */
    public SnapshotReport run() {
        LocalDate today = clock.instant().atZone(SNAPSHOT_ZONE).toLocalDate();
        MarketRound hk = runMarket(Market.HK, today, false);
        MarketRound us = runMarket(Market.US, today, isUsWideRound(today));
        String detail =
                "hk[rows=%d;industries=%d;src=%s] us[rows=%d;industries=%d;src=%s;promoted=%d;demoted=%d;mvMinUsd=%s]"
                        .formatted(
                                hk.snapshotRows(),
                                hk.industryRows(),
                                hk.sourceLabel(),
                                us.snapshotRows(),
                                us.industryRows(),
                                us.sourceLabel(),
                                us.promoted(),
                                us.demoted(),
                                usMvMinUsd());
        log.info("港美股行情快照轮完成 {}（Job 留痕摘要）", detail);
        return new SnapshotReport(
                hk.snapshotRows() + us.snapshotRows(),
                hk.industryRows() + us.industryRows(),
                us.promoted(),
                us.demoted(),
                detail);
    }

    /** 单市场一轮：取池 → 双源取数 → 快照 UPSERT → 行业聚合 UPSERT →（US 宽取轮）市值收敛。 */
    private MarketRound runMarket(Market market, LocalDate today, boolean wide) {
        List<Subject> pool = loadPool(market, wide);
        if (pool.isEmpty()) {
            log.warn("港美股快照池为空，本轮跳过 market={} wide={}", market, wide);
            return new MarketRound("none", 0, 0, 0, 0);
        }
        Fetched fetched = fetchWithFallback(market, pool);
        if (fetched == null) {
            return new MarketRound("failed", 0, 0, 0, 0);
        }
        if (market == Market.US && wide) {
            lastUsWideRoundDate = today;
        }
        int snapshotRows =
                upsertDailyRows(pool, fetched.rows(), today, market, fetched.sourceCode());
        int industryRows = upsertIndustryRows(pool, fetched.rows(), today, market);
        int promoted = 0;
        int demoted = 0;
        if (market == Market.US && wide) {
            int[] flips = convergeUsRepresentativeSet(pool, fetched.rows());
            promoted = flips[0];
            demoted = flips[1];
        }
        return new MarketRound(fetched.sourceCode(), snapshotRows, industryRows, promoted, demoted);
    }

    /** 当前轮是否美股宽取（每日首轮——市值收敛「升」级的唯一入口；当日已宽取或跨日即重新判定）。 */
    private boolean isUsWideRound(LocalDate today) {
        return !today.equals(lastUsWideRoundDate);
    }

    /** 取数池：HK = status=1（停用行不入采集，ADR-0064 裁决 3）；US 宽取轮全状态、后续轮 status=1。 */
    private List<Subject> loadPool(Market market, boolean wide) {
        List<Subject> bucket = subjectRepository.loadBucket(market, SubjectType.STOCK);
        if (wide) {
            return bucket;
        }
        return bucket.stream()
                .filter(subject -> subject.getStatus() == SubjectStatus.ENABLED)
                .toList();
    }

    /** 主备互切：腾讯整轮失败（异常或非空池零行）当轮切新浪（轮级降级非逐标的，ADR-0064 裁决 1）；双链全败返回 null（no-op）。 */
    private Fetched fetchWithFallback(Market market, List<Subject> pool) {
        List<String> codes =
                pool.stream().map(subject -> subject.getSubjectCode().value()).toList();
        Map<String, Map<String, Object>> rows;
        try {
            rows = fetchChunked(primarySource, codes);
            if (!rows.isEmpty()) {
                return new Fetched(primarySource.sourceCode(), rows);
            }
            log.warn("港美股主链（tencent）本轮零行 market={} pool={}，切备链（sina）", market, codes.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("港美股主链被中断 market={}: {}", market, e.toString());
        } catch (RuntimeException e) {
            log.warn("港美股主链（tencent）本轮失败，切备链（sina）market={}: {}", market, e.toString());
        }
        try {
            rows = fetchChunked(backupSource, codes);
            if (rows.isEmpty()) {
                log.warn(
                        "港美股备链（sina）本轮零行 market={} pool={}（双链无数据，本轮 no-op 不动旧快照）",
                        market,
                        codes.size());
                return null;
            }
            return new Fetched(backupSource.sourceCode(), rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("港美股备链被中断 market={}: {}", market, e.toString());
            return null;
        } catch (RuntimeException e) {
            log.warn("港美股备链（sina）本轮失败 market={}: {}（双链全败，本轮 no-op 不动旧快照）", market, e.toString());
            return null;
        }
    }

    /** 分块取数（50/请求 + 500ms 礼貌间隔，方案 §8 成本护栏）。 */
    private Map<String, Map<String, Object>> fetchChunked(
            HkusQuoteSource source, List<String> codes) throws InterruptedException {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i += REQUEST_BATCH_SIZE) {
            List<String> chunk = codes.subList(i, Math.min(i + REQUEST_BATCH_SIZE, codes.size()));
            merged.putAll(source.fetchBySubjectCodes(chunk));
            if (i + REQUEST_BATCH_SIZE < codes.size()) {
                Thread.sleep(INTER_REQUEST_INTERVAL_MILLIS);
            }
        }
        return merged;
    }

    /** 快照行 UPSERT（响应行缺席的标的不落行——NO_MARKET_DATA 口径由消费层判定，M20 同款）。 */
    private int upsertDailyRows(
            List<Subject> pool,
            Map<String, Map<String, Object>> rows,
            LocalDate today,
            Market market,
            String sourceCode) {
        String dateText = today.toString();
        String currency = currencyOf(market);
        List<MarketDailyRow> dailyRows = new ArrayList<>();
        for (Subject subject : pool) {
            Map<String, Object> f = rows.get(subject.getSubjectCode().value());
            if (f == null) {
                continue;
            }
            dailyRows.add(
                    new MarketDailyRow(
                            subject.getId(),
                            dateText,
                            doubleOf(f, "f43"),
                            doubleOf(f, "f170"),
                            doubleOf(f, "f168"),
                            doubleOf(f, "f171"),
                            doubleOf(f, "f47"),
                            positiveOrNull(f, "f162"),
                            positiveOrNull(f, "f167"),
                            sourceCode,
                            textOf(f, "f30"),
                            doubleOf(f, "market_cap"),
                            currency));
        }
        return dailyRows.isEmpty() ? 0 : dailySnapshotRepository.upsertAll(dailyRows);
    }

    /**
     * 行业就地聚合（市值加权 → 覆盖 &lt;80% 回退等权，agg_method 留痕；UNKNOWN 成员不进行业行）。 写侧走「替换写」——同事务清理该
     * market 当日本次未命中的旧行业行（M29 P2-01 修复：subject.industry 被 F10 回填改写后旧行不再残留，快照行数 = 实际行业数）。
     */
    private int upsertIndustryRows(
            List<Subject> pool,
            Map<String, Map<String, Object>> rows,
            LocalDate today,
            Market market) {
        Map<String, List<Subject>> byIndustry = new LinkedHashMap<>();
        for (Subject subject : pool) {
            if (!rows.containsKey(subject.getSubjectCode().value())) {
                continue;
            }
            String industry = subject.getIndustry();
            if (industry == null
                    || industry.isBlank()
                    || IndustryCategory.UNKNOWN_INDUSTRY.equals(industry)) {
                continue; // 未分类无行业语义（Spike-E §3.3 裁量：聚合行 = 各市场枚举集）
            }
            byIndustry.computeIfAbsent(industry, key -> new ArrayList<>()).add(subject);
        }
        if (byIndustry.isEmpty()) {
            return 0; // 空聚合轮 no-op 不清理（沿双链全败不动旧快照的保守语义）
        }
        String dateText = today.toString();
        String currency = currencyOf(market);
        List<MarketSnapshotRow> industryRows = new ArrayList<>(byIndustry.size());
        byIndustry.forEach(
                (industry, members) ->
                        industryRows.add(
                                aggregateIndustry(
                                        industry, members, rows, dateText, market, currency)));
        return industrySnapshotRepository.replaceIndustryRows(
                market.name(), dateText, industryRows);
    }

    /** 单行业聚合：pct_day 加权、涨跌家数、Σ市值（元原币）、agg_method 与 quote_time 留痕。 */
    private MarketSnapshotRow aggregateIndustry(
            String industry,
            List<Subject> members,
            Map<String, Map<String, Object>> rows,
            String dateText,
            Market market,
            String currency) {
        BigDecimal weightedPct = BigDecimal.ZERO;
        BigDecimal weightedCap = BigDecimal.ZERO;
        BigDecimal equalPct = BigDecimal.ZERO;
        int withPct = 0;
        int up = 0;
        int down = 0;
        BigDecimal totalCap = BigDecimal.ZERO;
        String quoteTime = null;
        for (Subject subject : members) {
            Map<String, Object> f = rows.get(subject.getSubjectCode().value());
            BigDecimal cap = decimalOf(f, "market_cap");
            BigDecimal pct = decimalOf(f, "f170");
            if (cap != null) {
                totalCap = totalCap.add(cap);
            }
            if (pct != null) {
                equalPct = equalPct.add(pct);
                withPct++;
                if (pct.doubleValue() > 0) {
                    up++;
                } else if (pct.doubleValue() < 0) {
                    down++;
                }
                if (cap != null) {
                    weightedPct = weightedPct.add(pct.multiply(cap));
                    weightedCap = weightedCap.add(cap);
                }
            }
            String time = textOf(f, "f30");
            if (time != null && (quoteTime == null || time.compareTo(quoteTime) > 0)) {
                quoteTime = time;
            }
        }
        String aggMethod = "NONE";
        Double pctDay = null;
        if (withPct > 0) {
            boolean capWeighted =
                    totalCap.signum() > 0
                            && weightedCap.doubleValue()
                                    >= totalCap.doubleValue() * MV_COVERAGE_FALLBACK_RATIO;
            BigDecimal value =
                    capWeighted
                            ? weightedPct.divide(weightedCap, 4, RoundingMode.HALF_UP)
                            : equalPct.divide(BigDecimal.valueOf(withPct), 4, RoundingMode.HALF_UP);
            pctDay = value.doubleValue();
            aggMethod = capWeighted ? "CAP_WEIGHTED" : "EQUAL";
        }
        Double totalMv = totalCap.signum() > 0 ? totalCap.multiply(YI_TO_YUAN).doubleValue() : null;
        return new MarketSnapshotRow(
                market.name(),
                "INDUSTRY",
                industry,
                industry,
                dateText,
                pctDay,
                null, // pct_d5：分市场交易日窗查询属 T255 读面，v1 留 NULL（类注）
                up,
                down,
                null, // main_net_flow：A 股通道专有，港美股如实不造假（V37 ② 注释）
                totalMv,
                currency,
                null, // leader_stock：港美股龙头 Won't（W1）
                AGG_SOURCE,
                aggMethod,
                quoteTime);
    }

    /** 美股代表集收敛：当轮有市值行的标的显式升降级（无市值行不动，避免误杀）；返回 [升级数, 降级数]。 */
    private int[] convergeUsRepresentativeSet(
            List<Subject> pool, Map<String, Map<String, Object>> rows) {
        double thresholdUsd = usMvMinUsd();
        List<String> keep = new ArrayList<>();
        List<String> demote = new ArrayList<>();
        for (Subject subject : pool) {
            BigDecimal capYi = decimalOf(rows.get(subject.getSubjectCode().value()), "market_cap");
            if (capYi == null) {
                continue; // 无市值行（字段缺失/备链 HK 形态）——本轮不动
            }
            double capUsd = capYi.multiply(YI_TO_YUAN).doubleValue();
            if (capUsd >= thresholdUsd) {
                keep.add(subject.getSubjectCode().value());
            } else {
                demote.add(subject.getSubjectCode().value());
            }
        }
        int promoted = subjectRepository.updateStatusForUsCodes(keep, true);
        int demoted = subjectRepository.updateStatusForUsCodes(demote, false);
        if (promoted > 0 || demoted > 0) {
            log.info(
                    "美股代表集市值收敛翻转 promoted={} demoted={} thresholdUsd={} keep={} demotePool={}",
                    promoted,
                    demoted,
                    thresholdUsd,
                    keep.size(),
                    demote.size());
        }
        return new int[] {promoted, demoted};
    }

    /** 收敛阈值热读（runtime_config 缺失/非法回落代码缺省 20 亿 USD；下界 1 亿防误配清空代表集）。 */
    double usMvMinUsd() {
        return runtimeConfigService
                .read(US_MV_MIN_CONFIG_KEY)
                .flatMap(HKUSMarketSnapshotService::parseUsMvMin)
                .orElse(DEFAULT_US_MV_MIN_USD);
    }

    private static Optional<Double> parseUsMvMin(RuntimeConfigEntry entry) {
        try {
            JsonNode node = CONFIG_MAPPER.readTree(entry.json()).path("usMvMinUsd");
            if (!node.isNumber()) {
                return Optional.empty();
            }
            double value = node.asDouble();
            return value >= US_MV_MIN_FLOOR_USD ? Optional.of(value) : Optional.empty();
        } catch (Exception e) {
            log.warn("美股收敛阈值配置不可解析，回落缺省 key={}: {}", US_MV_MIN_CONFIG_KEY, e.toString());
            return Optional.empty();
        }
    }

    private static String currencyOf(Market market) {
        return market == Market.HK ? "HKD" : "USD";
    }

    // ---- 中间结构取值（白名单语义：缺失/null 键不产出） ----

    private static Double doubleOf(Map<String, Object> fields, String key) {
        Object value = fields == null ? null : fields.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static BigDecimal decimalOf(Map<String, Object> fields, String key) {
        Object value = fields == null ? null : fields.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private static Double positiveOrNull(Map<String, Object> fields, String key) {
        Double value = doubleOf(fields, key);
        return value != null && value > 0 ? value : null; // 空/≤0 = 缺数（V30 列注释）
    }

    private static String textOf(Map<String, Object> fields, String key) {
        Object value = fields == null ? null : fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** 单市场取数结果（sourceCode 落行留痕：tencent / sina）。 */
    private record Fetched(String sourceCode, Map<String, Map<String, Object>> rows) {}

    /** 单市场轮结果（任务中心 detail 原料）。 */
    private record MarketRound(
            String sourceLabel, int snapshotRows, int industryRows, int promoted, int demoted) {}

    /** 轮次报告（JobRunStats 数据面）。 */
    public record SnapshotReport(
            int snapshotRows, int industryRows, int promoted, int demoted, String detail) {}
}
