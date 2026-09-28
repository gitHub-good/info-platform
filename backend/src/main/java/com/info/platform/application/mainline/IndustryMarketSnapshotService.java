package com.info.platform.application.mainline;

import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.IndustryQuote;
import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.domain.push.SourceAlertEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 行业行情快照编排（M27 T242，方案 §4.2.3 + ADR-0063 裁决 1/3）：双通道采集（主东财板块 push2 → 失败当轮切腾讯板块排行） → 板块聚合（通道 A）→
 * pct_d5 补算（通道 A 库内自算 / 通道 B 源直给）→ 当日行事务 UPSERT（幂等）→ 轮次统计留痕。
 *
 * <p><b>降级语义</b>（REQ 故事 1 场景 5）：双通道全败本轮 no-op + WARN + 连续失败计数进 lastRunDetail，<b>不动旧快照</b>（页面
 * heat-map meta.stale 标注）；连续 5 轮双通道失败发通知中心一条告警（复用 M14 源异常通知链路语义，文案标注
 * INDUSTRY_MARKET_SNAPSHOT，恢复前不重复）。
 *
 * <p><b>pct_d5 口径</b>（方案 §3.3 裁决 3）：近 5 个交易日 pct_day 复利累计（含当日实时行，交易日集 = 本表 distinct
 * snapshot_date）；冷启动不足 4 个前置交易日或窗内任一日缺值 → 该维 NULL（榜单缺维降权标注承接）。通道 B 取源 {@code zdf_d5} 直给（basis 留痕
 * d5=tencent）。
 */
@Service
public class IndustryMarketSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(IndustryMarketSnapshotService.class);

    /** 双通道连续失败告警阈值（方案 §4.2.3：连续 5 轮）。 */
    static final int DUAL_FAILURE_ALERT_THRESHOLD = 5;

    /** pct_d5 自算窗口（近 5 个交易日，含当日——方案 §3.3 裁决 3）。 */
    static final int PCT_D5_WINDOW_DAYS = 5;

    /** pct_d5 需要的前置交易日数（窗长 − 当日）。 */
    private static final int PCT_D5_PRIOR_DAYS = PCT_D5_WINDOW_DAYS - 1;

    /** 快照口径日时区（幂等锚）。 */
    static final ZoneId SNAPSHOT_ZONE = ZoneId.of("Asia/Shanghai");

    private final IndustryQuoteSource eastmoneySource;

    private final IndustryQuoteSource tencentSource;

    private final IndustryMarketSnapshotRepository repository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 双通道连续失败计数（成功轮清零；达阈值告警一次，恢复前不重复）。 */
    private int consecutiveDualFailures;

    private boolean alerting;

    public IndustryMarketSnapshotService(
            @Qualifier("eastMoneyBoardQuoteClient") IndustryQuoteSource eastmoneySource,
            @Qualifier("tencentBoardRankClient") IndustryQuoteSource tencentSource,
            IndustryMarketSnapshotRepository repository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.eastmoneySource = eastmoneySource;
        this.tencentSource = tencentSource;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 采集一轮（Job tick 与手动触发共用入口；快照口径日 = Asia/Shanghai 当日）。
     *
     * @return 轮次报告（行数 + detail——任务中心 lastRunDetail）
     */
    public SnapshotReport run() {
        IndustryQuoteBatch batch = fetchWithFallback();
        if (batch == null) {
            return onDualFailure();
        }
        consecutiveDualFailures = 0;
        boolean recovered = alerting;
        alerting = false;
        if (recovered) {
            log.info("行业行情快照双通道已恢复（此前连续失败 {} 轮告警在案）", DUAL_FAILURE_ALERT_THRESHOLD);
        }
        LocalDate today = clock.instant().atZone(SNAPSHOT_ZONE).toLocalDate();
        List<MarketSnapshotRow> rows = buildRows(batch, today.toString());
        int written = repository.upsertAll(rows);
        String detail =
                "source="
                        + batch.source()
                        + ";boards="
                        + batch.boards().size()
                        + ";industries="
                        + batch.industries().size()
                        + ";unmappedWarn="
                        + batch.unmappedBoards()
                        + ";pctD5="
                        + (batch.source().equals("tencent-rank") ? "tencent" : "self");
        log.info("行业行情快照落库完成 rows={} {}（Job 留痕摘要）", written, detail);
        return new SnapshotReport(written, detail);
    }

    /** 主备互切：A 失败当轮即切 B（不设熔断计数——轮级切换成本 = 1 次请求，ADR-0063 裁决 1）。 */
    private IndustryQuoteBatch fetchWithFallback() {
        try {
            return eastmoneySource.fetch();
        } catch (RuntimeException e) {
            log.warn("行业行情通道 A（eastmoney-push2）本轮失败，切通道 B: {}", e.toString());
        }
        try {
            return tencentSource.fetch();
        } catch (RuntimeException e) {
            log.warn("行业行情通道 B（tencent-rank）本轮失败: {}", e.toString());
            return null;
        }
    }

    /** 双通道全败：no-op 不动旧快照（stale 标注承接）+ 计数 + 阈值告警一次。 */
    private SnapshotReport onDualFailure() {
        consecutiveDualFailures++;
        String detail = "双通道失败连续 " + consecutiveDualFailures + " 轮（本轮 no-op，沿用旧快照——页面 stale 标注）";
        log.warn("行业行情双通道全败：{}", detail);
        if (consecutiveDualFailures >= DUAL_FAILURE_ALERT_THRESHOLD && !alerting) {
            alerting = true;
            eventPublisher.publishEvent(
                    new SourceAlertEvent(
                            SourceAlertEvent.Kind.ALERT,
                            "INDUSTRY_MARKET_SNAPSHOT",
                            "行业行情快照",
                            consecutiveDualFailures,
                            null,
                            "东财 push2 板块与腾讯板块排行双通道连续失败，行情快照停留在旧值",
                            clock.instant()));
            log.error(
                    "行业行情快照连续 {} 轮双通道失败，已发通知中心告警 INDUSTRY_MARKET_SNAPSHOT",
                    consecutiveDualFailures);
        }
        return new SnapshotReport(0, detail);
    }

    /** 组装落库行：板块行（agg_method=NONE）+ 行业行（pct_d5 补算）。 */
    private List<MarketSnapshotRow> buildRows(IndustryQuoteBatch batch, String today) {
        Map<String, Map<String, Double>> history = loadHistoryForPctD5(batch, today);
        List<MarketSnapshotRow> rows =
                new ArrayList<>(batch.boards().size() + batch.industries().size());
        batch.boards()
                .forEach(
                        board ->
                                rows.add(
                                        new MarketSnapshotRow(
                                                "BOARD",
                                                board.boardName(),
                                                board.industry(),
                                                today,
                                                board.pctDay(),
                                                null,
                                                board.upCount(),
                                                board.downCount(),
                                                board.mainNetFlow(),
                                                board.totalMv(),
                                                null,
                                                batch.source(),
                                                "NONE",
                                                batch.quoteTime())));
        boolean selfComputePctD5 = history != null;
        for (IndustryQuote industry : batch.industries()) {
            Double pctD5 = industry.pctD5();
            if (pctD5 == null && selfComputePctD5) {
                pctD5 = compoundPctD5(history.get(industry.industry()), industry.pctDay());
            }
            rows.add(
                    new MarketSnapshotRow(
                            "INDUSTRY",
                            industry.industry(),
                            industry.industry(),
                            today,
                            industry.pctDay(),
                            pctD5,
                            industry.upCount(),
                            industry.downCount(),
                            industry.mainNetFlow(),
                            industry.totalMv(),
                            industry.leaderStock() == null
                                    ? null
                                    : leaderStockJson(industry.leaderStock()),
                            batch.source(),
                            industry.aggMethod(),
                            batch.quoteTime()));
        }
        return rows;
    }

    /** 通道 A 前置历史装载（近 5 交易日窗）。返回 null = 无需自算（通道 B 直给）；历史不足 4 前置日也返回装载结果（不足者逐行业 NULL——冷启动语义）。 */
    private Map<String, Map<String, Double>> loadHistoryForPctD5(
            IndustryQuoteBatch batch, String today) {
        if (batch.source().equals("tencent-rank")) {
            return null;
        }
        List<String> recent = repository.recentSnapshotDates(PCT_D5_WINDOW_DAYS);
        List<String> priorDates =
                recent.stream()
                        .filter(date -> date.compareTo(today) < 0)
                        .limit(PCT_D5_PRIOR_DAYS)
                        .toList();
        if (priorDates.size() < PCT_D5_PRIOR_DAYS) {
            // 冷启动：无足够历史 → 全行业该维 NULL（缺维降权标注由榜单承接）
            return Map.of();
        }
        Map<String, Map<String, Double>> history = new HashMap<>();
        for (HistoryPctDay row : repository.findIndustryPctDayForDates(priorDates)) {
            if (row.pctDay() != null) {
                history.computeIfAbsent(row.industry(), key -> new HashMap<>())
                        .put(row.snapshotDate(), row.pctDay());
            }
        }
        return history;
    }

    /** pct_d5 复利自算：[Π(1+pct/100) − 1]×100（时间升序累计；任一日缺值 → null，不半窗凑数）。 */
    private static Double compoundPctD5(Map<String, Double> historyByDate, Double todayPct) {
        if (historyByDate == null || historyByDate.size() < PCT_D5_PRIOR_DAYS || todayPct == null) {
            return null;
        }
        double factor = 1d;
        for (Double pct : historyByDate.values()) {
            factor *= 1d + pct / 100d;
        }
        factor *= 1d + todayPct / 100d;
        return (factor - 1d) * 100d;
    }

    /** 领涨股 JSON（DDL 注释契约 {@code {"code","name","pct"}}；领域不依赖 JSON 库——手工拼串，三字段均源侧可控）。 */
    private static String leaderStockJson(com.info.platform.domain.mainline.LeaderStock leader) {
        String code = leader.code() == null ? "" : leader.code().replace("\"", "");
        String name = leader.name() == null ? "" : leader.name().replace("\"", "");
        String pct = leader.pct() == null ? "null" : String.valueOf(leader.pct());
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"pct\":" + pct + "}";
    }

    /** 轮次报告（JobRunStats 上报口径）。 */
    public record SnapshotReport(int snapshotRows, String detail) {}
}
