package com.info.platform.application.valuation;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 评分查询服务（应用层，M20 方案 §4.7.2 coverage 对账口径）：快照行数 vs 活跃标的数数量对账（无资金语义， 方案库 09 裁剪）——M20
 * 验收口径常驻。日期缺省最新快照日；非法日期 30088。
 */
@Service
public class ValueScoreQueryService {

    private final FactorSnapshotRepository repository;

    private final MarketDailySnapshotRepository marketRepository;

    public ValueScoreQueryService(
            FactorSnapshotRepository repository, MarketDailySnapshotRepository marketRepository) {
        this.repository = repository;
        this.marketRepository = marketRepository;
    }

    /**
     * 覆盖对账（GET /api/v1/value-scores/coverage）。
     *
     * @param dateParam yyyy-MM-dd（null/空 = 最新快照日；非法 400/30088）
     */
    public CoverageView coverage(String dateParam) {
        String date = resolveDate(dateParam);
        long activeSubjects = repository.countActiveSubjects();
        if (date == null) {
            // Job 未跑过：空口径如实返回（不伪造数据）
            return new CoverageView(
                    null, activeSubjects, 0, activeSubjects, 0.0, 0, emptyFlagCounts());
        }
        long snapshotRows = repository.countByDate(date);
        long marketRows = marketRepository.countByDate(date);
        long missing = Math.max(0, activeSubjects - snapshotRows);
        double coverageRate =
                activeSubjects == 0
                        ? 0.0
                        : round1(100.0 * Math.min(snapshotRows, activeSubjects) / activeSubjects);
        return new CoverageView(
                date,
                activeSubjects,
                snapshotRows,
                missing,
                coverageRate,
                marketRows,
                flagCountsOf(date));
    }

    private String resolveDate(String dateParam) {
        if (dateParam == null || dateParam.isBlank()) {
            return repository.findLatestSnapshotDate().orElse(null);
        }
        try {
            LocalDate.parse(dateParam); // 严格 yyyy-MM-dd（DateTimeParseException → 30088）
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.VALUE_SCORE_QUERY_INVALID, "非法日期（需 yyyy-MM-dd）: " + dateParam);
        }
        return dateParam;
    }

    private Map<String, Long> flagCountsOf(String date) {
        Map<String, Long> flagCounts = new LinkedHashMap<>();
        for (String flag : FactorSnapshotService.CANONICAL_FLAGS) {
            flagCounts.put(flag, repository.countFlagged(date, flag));
        }
        return flagCounts;
    }

    private static Map<String, Long> emptyFlagCounts() {
        Map<String, Long> flagCounts = new LinkedHashMap<>();
        for (String flag : FactorSnapshotService.CANONICAL_FLAGS) {
            flagCounts.put(flag, 0L);
        }
        return flagCounts;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 覆盖对账视图（§4.7.2 契约）。 */
    public record CoverageView(
            String snapshotDate,
            long activeSubjects,
            long snapshotRows,
            long missingCount,
            double coverageRate,
            long marketDataRows,
            Map<String, Long> flagCounts) {}
}
