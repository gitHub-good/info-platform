package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.markettop.MarketTopRepository.VersionSummary;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.ValuationParams;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 榜单读取服务（应用层，M21 T183，方案 §4.7.1）：最新版本/指定版本读取 + 五维分解补全（快照行 f 列 + weight_basis 回读权重——榜单行不冗余存
 * factors，读时按 (subjectId, snapshotDate) join 复现）+ 历史版本列表。错误口径：30089 无榜单 / 30090 参数非法或版本不存在。
 */
@Service
public class MarketTopQueryService {

    /** 榜单页级免责常驻（§4.8 三处必载之一）。 */
    static final String DISCLAIMER = "榜单为多因子信息整理与 AI 摘要，不构成投资建议";

    private static final Logger log = LoggerFactory.getLogger(MarketTopQueryService.class);

    private final MarketTopRepository repository;

    private final FactorSnapshotRepository snapshotRepository;

    private final ObjectMapper objectMapper;

    public MarketTopQueryService(
            MarketTopRepository repository,
            FactorSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 榜单详情（GET /api/v1/market-top?date=&version=）。
     *
     * @param dateParam yyyy-MM-dd（null/空 = 最新有榜单日；非法 30090/400）
     * @param versionParam 版本号（null = 该日最大 version；不存在 30090）
     * @throws BusinessException 30089 请求日无任何榜单；30090 参数非法或版本不存在
     */
    public RankView rank(String dateParam, String versionParam) {
        Integer version = parseVersion(versionParam);
        Optional<MarketTopVersion> found = find(dateParam, version);
        if (found.isEmpty()) {
            throw notFoundOrInvalid(dateParam, version);
        }
        return toView(found.get());
    }

    /** 历史版本列表（GET /api/v1/market-top/versions?date=）。 */
    public List<VersionSummary> versions(String dateParam) {
        String date = dateParam == null || dateParam.isBlank() ? null : parseDate(dateParam);
        return repository.listVersions(date, MarketTopService.VERSIONS_LIMIT);
    }

    // ---- 内部组装 ----

    private Optional<MarketTopVersion> find(String dateParam, Integer version) {
        if (dateParam == null || dateParam.isBlank()) {
            return version == null
                    ? repository.findLatestAnyDate()
                    : repository
                            .findLatestAnyDate()
                            .flatMap(latest -> repository.find(latest.batch().rankDate(), version));
        }
        String date = parseDate(dateParam);
        return version == null ? repository.findLatest(date) : repository.find(date, version);
    }

    private BusinessException notFoundOrInvalid(String dateParam, Integer version) {
        if (version != null && dateParam != null && !dateParam.isBlank()) {
            return new BusinessException(
                    ErrorCode.MARKET_TOP_QUERY_INVALID,
                    "榜单版本不存在: date=" + dateParam + ", version=" + version);
        }
        return new BusinessException(
                ErrorCode.MARKET_TOP_NOT_FOUND,
                "该日无榜单数据: date=" + (dateParam == null || dateParam.isBlank() ? "<最新>" : dateParam));
    }

    private RankView toView(MarketTopVersion found) {
        Map<Long, List<FactorView>> factorsBySubject =
                factorsOf(found.batch().snapshotDate(), found.items());
        List<ItemView> items = new ArrayList<>(found.items().size());
        for (MarketTopRankRow row : found.items()) {
            items.add(
                    new ItemView(
                            row.rankNo(),
                            row.subjectId(),
                            row.subjectCode(),
                            row.subjectName(),
                            row.totalScore(),
                            row.finalScore(),
                            row.percentile(),
                            row.breakthrough(),
                            factorsBySubject.getOrDefault(row.subjectId(), List.of()),
                            row.generation(),
                            row.diveMethod(),
                            row.diveSummary(),
                            readTree(row.diveDetailJson()),
                            row.evidenceCount(),
                            row.lastEventDate(),
                            row.prevRank(),
                            row.changeType(),
                            row.computedAt()));
        }
        return new RankView(
                found.batch().rankDate(),
                found.batch().version(),
                found.batch().triggerSource(),
                new BatchView(
                        found.batch().snapshotDate(),
                        found.batch().createdAt(),
                        found.batch().degraded(),
                        found.batch().degradedReason(),
                        readTree(found.batch().funnelStatsJson()),
                        readTree(found.batch().droppedSubjectsJson()),
                        null),
                items,
                DISCLAIMER,
                recentIncrementOf(found.batch().rankDate()));
    }

    /**
     * 页头「最近增量重评」（M22 T192，方案 §4.2-② 时间戳双层语义）：当日最新 EVENT 版本摘要（无则 null——前端仅呈现全量 口径）；trigger_events
     * JSON 容错解析（损坏空表不阻断榜单读取）。
     */
    private RecentIncrementView recentIncrementOf(String rankDate) {
        return repository
                .findLatestEventVersion(rankDate)
                .map(
                        version ->
                                new RecentIncrementView(
                                        version.version(),
                                        version.createdAtIso(),
                                        triggerEventsOf(version.triggerEventsJson())))
                .orElse(null);
    }

    /** trigger_events JSON（[{eventId,summary,importance}]）→ 视图（空/损坏 → 空表）。 */
    private List<TriggerEventView> triggerEventsOf(String triggerEventsJson) {
        JsonNode array = readTree(triggerEventsJson);
        List<TriggerEventView> events = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                events.add(
                        new TriggerEventView(
                                node.path("eventId").asLong(),
                                node.path("summary").isMissingNode()
                                        ? null
                                        : node.path("summary").asText(null),
                                node.path("importance").isMissingNode()
                                        ? null
                                        : node.path("importance").asText(null)));
            }
        }
        return events;
    }

    /** 五维分解补全（快照行 f 列 + weight_basis 当时权重——复现面与价值评分端点同构）。 */
    private Map<Long, List<FactorView>> factorsOf(
            String snapshotDate, List<MarketTopRankRow> items) {
        Map<Long, List<FactorView>> factorsBySubject = new HashMap<>();
        List<PoolRow> rows = snapshotRepository.findPoolRowsByDate(snapshotDate);
        Map<Long, PoolRow> rowById = new HashMap<>();
        for (PoolRow row : rows) {
            rowById.put(row.subjectId(), row);
        }
        for (MarketTopRankRow item : items) {
            PoolRow row = rowById.get(item.subjectId());
            if (row == null) {
                continue; // 快照已被滚动/清理——空分解如实（factors 可缺省渲染）
            }
            ValuationParams params = ValuationParams.fromBasis(row.weightBasis());
            factorsBySubject.put(
                    item.subjectId(),
                    List.of(
                            new FactorView("catalyst", "事件催化", row.fCatalyst(), params.wCatalyst()),
                            new FactorView(
                                    "conduction", "行业传导", row.fConduction(), params.wConduction()),
                            new FactorView(
                                    "fundamental",
                                    "基本面边际",
                                    row.fFundamental(),
                                    params.wFundamental()),
                            new FactorView("risk", "风险安全", row.fRisk(), params.wRisk()),
                            new FactorView(
                                    "valuation", "估值水平", row.fValuation(), params.wValuation())));
        }
        return factorsBySubject;
    }

    private static String parseDate(String dateParam) {
        try {
            return LocalDate.parse(dateParam).toString();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(
                    ErrorCode.MARKET_TOP_QUERY_INVALID, "非法日期（需 yyyy-MM-dd）: " + dateParam);
        }
    }

    private static Integer parseVersion(String versionParam) {
        if (versionParam == null || versionParam.isBlank()) {
            return null;
        }
        try {
            int version = Integer.parseInt(versionParam.trim());
            if (version < 1) {
                throw new NumberFormatException("version < 1");
            }
            return version;
        } catch (NumberFormatException e) {
            throw new BusinessException(
                    ErrorCode.MARKET_TOP_QUERY_INVALID, "非法版本号（需正整数）: " + versionParam);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            log.warn("榜单 JSON 列解析失败（回退空对象）: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** 五维分解条目（与价值评分端点同构）。 */
    record FactorView(String key, String name, double score, double weight) {}

    /** 榜单卡视图（§4.7.1 契约 items 元素）。 */
    record ItemView(
            int rankNo,
            long subjectId,
            String subjectCode,
            String subjectName,
            double totalScore,
            double finalScore,
            Double percentile,
            boolean breakthrough,
            List<FactorView> factors,
            String generation,
            String diveMethod,
            String diveSummary,
            JsonNode diveDetail,
            int evidenceCount,
            String lastEventDate,
            Integer prevRank,
            String changeType,
            String computedAt) {}

    /** batch 视图（降级横幅/漏斗/跌出留痕——页面数据面）。 */
    public record BatchView(
            String snapshotDate,
            String computedAt,
            boolean degraded,
            String degradedReason,
            JsonNode funnelStats,
            JsonNode dropped,
            String lastEvent) {}

    /** 榜单详情视图（§4.7.1 契约 + recentIncrement 页头双时间戳块 M22 T192）。 */
    public record RankView(
            String rankDate,
            int version,
            String triggerSource,
            BatchView batch,
            List<ItemView> items,
            String disclaimer,
            RecentIncrementView recentIncrement) {}

    /** 页头「最近增量重评」视图（§4.2-②：当日最新 EVENT 版本——无则 null，双层口径「全量日频盘后 + 事件分钟级增量」）。 */
    public record RecentIncrementView(
            int version, String computedAt, List<TriggerEventView> triggerEvents) {}

    /** EVENT 版本触发事件（trace-v1 下钻——eventId 跳事件流 focus）。 */
    public record TriggerEventView(long eventId, String summary, String importance) {}
}
