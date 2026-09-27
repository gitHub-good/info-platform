package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.ValuationParams;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 评分查询服务（应用层，M20 方案 §4.7）：coverage 数量对账（T170，验收口径常驻）+ 标的价值评分详情（T171）—— 最新快照 + 五维分解（权重按当时
 * weight_basis 回读，不被当前配置改写）+ 全市场百分位查询层算（rank = 严格大于 + 1， 并列同名次——不入库不进合成，ADR-0058 裁决 3）+
 * 依据明细透传（trace-v1 下钻原料）。
 */
@Service
public class ValueScoreQueryService {

    /** 区块级免责一行常驻（§4.8；页级三处必载在 M21）。 */
    static final String DISCLAIMER = "评分为多因子信息整理，不构成投资建议";

    private static final Logger log = LoggerFactory.getLogger(ValueScoreQueryService.class);

    private final FactorSnapshotRepository repository;

    private final MarketDailySnapshotRepository marketRepository;

    private final IncrementalReevalRepository reevalRepository;

    private final ObjectMapper objectMapper;

    public ValueScoreQueryService(
            FactorSnapshotRepository repository,
            MarketDailySnapshotRepository marketRepository,
            IncrementalReevalRepository reevalRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.marketRepository = marketRepository;
        this.reevalRepository = reevalRepository;
        this.objectMapper = objectMapper;
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

    /**
     * 标的价值评分（GET /api/v1/subjects/{subjectId}/value-score，§4.7.1）：最新快照行 + 查询层百分位 + 五维分解（权重按快照行
     * weight_basis 回读——历史快照按当时权重复现）。
     *
     * @throws BusinessException 30086 该标的无任何快照（Job 未跑过/标的不存在）
     */
    public ScoreView valueScore(long subjectId) {
        FactorSnapshotRow row =
                repository
                        .findLatestBySubject(subjectId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.VALUE_SCORE_NOT_FOUND,
                                                "标的无评分快照: subjectId=" + subjectId));
        long total = repository.countByDate(row.snapshotDate());
        long rank = repository.countScoreGreaterThan(row.snapshotDate(), row.totalScore()) + 1;
        long percentile = Math.round(100.0 * (total - rank) / Math.max(1, total - 1));
        ValuationParams params = ValuationParams.fromBasis(row.weightBasis());
        log.debug(
                "价值评分查询 subjectId={} date={} total={} rank={}/{}",
                subjectId,
                row.snapshotDate(),
                row.totalScore(),
                rank,
                total);
        return new ScoreView(
                row.subjectId(),
                row.snapshotDate(),
                row.totalScore(),
                row.breakthrough(),
                rank,
                percentile,
                factorsOf(row, params),
                readTree(row.factorDetailJson()),
                flagsOf(row.dataFlagsJson()),
                row.weightBasis(),
                row.computedAtIso(),
                DISCLAIMER,
                incrementOf(subjectId, row.snapshotDate()));
    }

    /**
     * 事件驱动增量覆盖块（M22 T192，方案 §4.2-① 时间戳双层语义）：当日行 {@code increment_at} 非空才呈现—— {@code updatedAt} =
     * 增量覆盖时刻、events = 该轮触发事件（留痕表 {@code snapshot_at} 精确反查，一轮多事件同刻）； 未覆盖标的返回 null →
     * 前端显示当日快照基准时刻无标注（故事 4 场景 2）。
     */
    private IncrementView incrementOf(long subjectId, String snapshotDate) {
        return repository
                .findIncrementAt(subjectId, snapshotDate)
                .map(
                        incrementAt -> {
                            List<IncrementEventView> events =
                                    reevalRepository.findRoundEvents(incrementAt).stream()
                                            .map(
                                                    event ->
                                                            new IncrementEventView(
                                                                    event.eventId(),
                                                                    event.summary(),
                                                                    event.importance(),
                                                                    event.eventDate()))
                                            .toList();
                            log.debug(
                                    "评分增量块 subjectId={} incrementAt={} events={}",
                                    subjectId,
                                    incrementAt,
                                    events.size());
                            return new IncrementView(incrementAt, events);
                        })
                .orElse(null);
    }

    // ---- value-score 组装 ----

    private List<FactorView> factorsOf(FactorSnapshotRow row, ValuationParams params) {
        boolean valuationNeutral = valuationBasisMissing(row);
        List<FactorView> factors = new ArrayList<>(5);
        factors.add(new FactorView("catalyst", "事件催化", row.fCatalyst(), params.wCatalyst(), false));
        factors.add(
                new FactorView(
                        "conduction", "行业传导", row.fConduction(), params.wConduction(), false));
        factors.add(
                new FactorView(
                        "fundamental", "基本面边际", row.fFundamental(), params.wFundamental(), false));
        factors.add(new FactorView("risk", "风险安全", row.fRisk(), params.wRisk(), false));
        factors.add(
                new FactorView(
                        "valuation",
                        "估值水平",
                        row.fValuation(),
                        params.wValuation(),
                        valuationNeutral));
        return factors;
    }

    /** 估值维缺数态（detail.valuation.basis 为 null → 前端「未启用/缺数」弱化依据）。 */
    private boolean valuationBasisMissing(FactorSnapshotRow row) {
        JsonNode basis = readTree(row.factorDetailJson()).path("valuation").path("basis");
        return basis.isMissingNode() || basis.isNull();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            log.warn("评分明细 JSON 解析失败（回退空对象）: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private List<String> flagsOf(String dataFlagsJson) {
        try {
            return objectMapper.readValue(
                    dataFlagsJson == null ? "[]" : dataFlagsJson,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- coverage 组装 ----

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

    /** 五维分解条目（weight 按当时 weight_basis；neutral 仅估值维缺数态为 true）。 */
    public record FactorView(
            String key, String name, double score, double weight, boolean neutral) {}

    /** 标的价值评分视图（§4.7.1 契约：总分/标签/排名百分位/分解/明细/flags/指纹/免责 + increment 增量块 M22）。 */
    public record ScoreView(
            long subjectId,
            String snapshotDate,
            double totalScore,
            boolean breakthrough,
            long rank,
            long percentile,
            List<FactorView> factors,
            JsonNode detail,
            List<String> dataFlags,
            String weightBasis,
            String computedAt,
            String disclaimer,
            IncrementView increment) {}

    /** 事件驱动增量覆盖块（§4.2-①：updatedAt = increment_at；事件清单空如实——留痕可能已过生命周期）。 */
    public record IncrementView(String updatedAt, List<IncrementEventView> events) {}

    /** 增量触发事件（留痕表反查 event_item 摘要面——eventId 跳事件流 focus，trace-v1 下钻）。 */
    public record IncrementEventView(
            long eventId, String summary, String importance, String eventDate) {}
}
