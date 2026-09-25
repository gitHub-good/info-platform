package com.info.platform.application.analysis;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 疑似停更检查服务（应用层，M15 T128，REQ AMB-01 / 方案 §4.7）：各启用源滚动窗内净入库（{@code source_daily_stats}
 * Σnew_count）判定——零净入库且源龄满窗 → {@code info_source.config.staleSince} 写入标记日（Job 独占热写，零 DDL——ADR-0049
 * 裁量 6）；窗内任一日净入库 &gt; 0 → 自动清除标记。**不自动停用**（展示态徽章语义）。
 *
 * <p>频控分级（ADR-0049 裁量 7）：缺省窗 7 天（预置源周 ≥1 频控）；月频源（东财宏观指标序列）走 35 天窗——清单常量对齐 {@code
 * FeedDashboardService.DAILY_GRANULARITY_CODES} 先例。源龄不满窗的新建源不标记（避免「上线即停更」误报）。
 */
@Service
public class SourceStaleCheckService {

    private static final Logger log = LoggerFactory.getLogger(SourceStaleCheckService.class);

    /** 统计日界（Asia/Shanghai——source_daily_stats.stat_date 口径同源）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 缺省停更窗（天）：连续 7 天净入库 0（REQ AMB-01）。 */
    static final int DEFAULT_WINDOW_DAYS = 7;

    /** 月频源停更窗（天）：35 天（月更源 7 天窗必然误报，ADR-0049 裁量 7）。 */
    static final int MONTHLY_WINDOW_DAYS = 35;

    /**
     * 月频源清单（sourceCode）：东财宏观指标序列月更——新月频源在此登记（常量先例：FeedDashboardService.DAILY_GRANULARITY_CODES）。
     */
    static final List<String> MONTHLY_SOURCE_CODES = List.of("em_macro_indicators");

    private final InfoSourceRepository infoSourceRepository;
    private final SourceDailyStatsRepository statsRepository;
    private final Clock clock;

    public SourceStaleCheckService(
            InfoSourceRepository infoSourceRepository,
            SourceDailyStatsRepository statsRepository,
            Clock clock) {
        this.infoSourceRepository = infoSourceRepository;
        this.statsRepository = statsRepository;
        this.clock = clock;
    }

    /**
     * 全量检查一轮（定时与手动触发共用入口）：标记 / 解除 / 留痕计数。
     *
     * @return 轮报告（JobRunStats 留痕数据面）
     */
    public StaleCheckReport checkAll() {
        LocalDate todayDate = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        String today = todayDate.toString();
        String earliestWindowStart = todayDate.minusDays(MONTHLY_WINDOW_DAYS).toString();
        Map<Long, List<SourceDailyStats>> statsBySource =
                statsRepository.findSince(earliestWindowStart).stream()
                        .collect(Collectors.groupingBy(SourceDailyStats::sourceId));

        int marked = 0;
        int cleared = 0;
        List<String> markedCodes = new ArrayList<>();
        List<String> clearedCodes = new ArrayList<>();
        List<InfoSource> sources = infoSourceRepository.findActive();
        for (InfoSource source : sources) {
            int windowDays = windowDaysFor(source.getSourceCode());
            String windowStart = todayDate.minusDays(windowDays).toString();
            long netNew =
                    statsBySource
                            .getOrDefault(source.getId() == null ? -1L : source.getId(), List.of())
                            .stream()
                            .filter(row -> row.statDate().compareTo(windowStart) >= 0)
                            .mapToLong(SourceDailyStats::newCount)
                            .sum();
            boolean ageEnough = ageInDays(source.getCreatedAt()) > windowDays;
            String current = source.getConfig().staleSince();
            if (netNew == 0 && ageEnough) {
                if (current == null) {
                    source.edit(
                            null, null, null, source.getConfig().withStaleSince(today), null, null);
                    infoSourceRepository.save(source);
                    marked++;
                    markedCodes.add(source.getSourceCode());
                    log.info(
                            "疑似停更标记 source={} window={}天 staleSince={}",
                            source.getSourceCode(),
                            windowDays,
                            today);
                }
            } else if (netNew > 0 && current != null) {
                source.edit(null, null, null, source.getConfig().withStaleSince(null), null, null);
                infoSourceRepository.save(source);
                cleared++;
                clearedCodes.add(source.getSourceCode());
                log.info("疑似停更解除 source={}（窗内净入库 {} 条）", source.getSourceCode(), netNew);
            }
        }
        String detail =
                "checked="
                        + sources.size()
                        + "; marked="
                        + marked
                        + (markedCodes.isEmpty() ? "" : "(" + String.join(",", markedCodes) + ")")
                        + "; cleared="
                        + cleared
                        + (clearedCodes.isEmpty()
                                ? ""
                                : "(" + String.join(",", clearedCodes) + ")");
        return new StaleCheckReport(sources.size(), marked, cleared, detail);
    }

    /** 源频控窗（天）：月频清单走 35 天，其余缺省 7 天。 */
    private static int windowDaysFor(String sourceCode) {
        return MONTHLY_SOURCE_CODES.contains(sourceCode)
                ? MONTHLY_WINDOW_DAYS
                : DEFAULT_WINDOW_DAYS;
    }

    /** 源龄（天，上海日界取整）：createdAt 缺失视为 0（保守不标记）。 */
    private long ageInDays(java.time.Instant createdAt) {
        if (createdAt == null) {
            return 0L;
        }
        return ChronoUnit.DAYS.between(
                LocalDate.ofInstant(createdAt, STAT_ZONE),
                LocalDate.ofInstant(clock.instant(), STAT_ZONE));
    }

    /** 停更检查轮报告。 */
    public record StaleCheckReport(int checked, int marked, int cleared, String detail) {}
}
