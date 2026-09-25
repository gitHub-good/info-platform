package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.push.PipelineFusedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 管道成本护栏服务（应用层，M15 T125，方案 §3.5/§4.6 裁决 5）：{@code llm_call_log} scene 5/6/7 当日 SUCCESS 求和现算成本 → 对
 * {@code pipeline.budget} 两级阈值派生级别（无表无状态，重启不丢口径——ADR-0015「严格口径以留痕表为准」先例）；FUSED 进入时发布 {@link
 * PipelineFusedEvent} 告警一次（内存节流，重启重告一次可容忍——ADR-0045 同款裁量）。
 *
 * <p>日界 = Asia/Shanghai（统计语义优先本地日）；换日查询窗口自动滚动 = 次日首 tick 自然恢复，零恢复代码。单条成本校准（REQ 拍板四-4）： 按「Σ管道成本 ÷
 * 当日净入库条数」写回 {@code pipeline.budget}（calibratedPerItemMicros + costBasis 升版 {@code
 * cost-v2:calibrated:<date>}，变更留痕不静默）。
 */
@Service
public class PipelineGuardService {

    private static final Logger log = LoggerFactory.getLogger(PipelineGuardService.class);

    /** 统计日界（Asia/Shanghai——与 PipelineStatusService 同口径）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 管道成本口径 scene 集（L1 归类 5 / L2 事件提取 6 / 行业日报 7——T124 消费 7）。 */
    static final List<String> PIPELINE_SCENES = List.of("5", "6", "7");

    /** 成本读取 llm_call_log 上限护栏（个人量级日 ~90 行，上限防御）。 */
    private static final int COST_LOG_LIMIT = 10_000;

    /** 校准 basis 前缀（初值 cost-v1:initial 由 PipelineSettings 缺省承载）。 */
    private static final String CALIBRATED_BASIS_PREFIX = "cost-v2:calibrated:";

    private final LlmCallLogRepository llmCallLogRepository;
    private final NewsAnalysisRepository newsAnalysisRepository;
    private final RuntimeConfigService configService;
    private final PipelineSettings settings;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** FUSED 告警节流：episodeKey（当日上海日期）→ 已告警标记（换日自动重新武装）。 */
    private final ConcurrentHashMap<String, Boolean> fusedAlertedEpisodes =
            new ConcurrentHashMap<>();

    public PipelineGuardService(
            LlmCallLogRepository llmCallLogRepository,
            NewsAnalysisRepository newsAnalysisRepository,
            RuntimeConfigService configService,
            PipelineSettings settings,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.llmCallLogRepository = llmCallLogRepository;
        this.newsAnalysisRepository = newsAnalysisRepository;
        this.configService = configService;
        this.settings = settings;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 当前护栏级别（每 tick 批执行前调用；成本现算派生，无表无状态）。 */
    public GuardLevel currentLevel() {
        long cost = todayCostMicros();
        long budget = settings.dailyBudgetMicros();
        GuardLevel level = deriveLevel(cost, budget);
        if (level == GuardLevel.FUSED) {
            publishFusedAlertOnce(cost, budget);
        }
        return level;
    }

    /** 当日管道成本（微元；scene 5/6/7 SUCCESS，Asia/Shanghai 日界）。 */
    public long todayCostMicros() {
        List<LlmCallLog> logs =
                llmCallLogRepository.findCreatedSince(todayStartInstant(), COST_LOG_LIMIT);
        return logs.stream()
                .filter(log -> PIPELINE_SCENES.contains(log.getSceneKey()))
                .filter(log -> log.getStatus() == LlmCallStatus.SUCCESS)
                .mapToLong(LlmCallLog::getCostMicros)
                .sum();
    }

    /**
     * 单条成本校准：Σ当日管道成本 ÷ 当日净入库条数 → 写回 {@code pipeline.budget}（整体替换语义保留既有字段；costBasis 升版带当日日期）。
     *
     * @return 写入的校准值（微元/条）；当日无入库（分母 0）返回 -1 不写
     */
    public long calibratePerItemCost() {
        String todayStartIso = todayStartInstant().toString();
        long newsCount = newsAnalysisRepository.countNewsItemsCreatedSince(todayStartIso);
        if (newsCount <= 0) {
            log.info("管道单条成本校准跳过：当日无净入库条数");
            return -1L;
        }
        long cost = todayCostMicros();
        long perItem = cost / newsCount;
        writeCalibration(perItem, LocalDate.ofInstant(clock.instant(), STAT_ZONE).toString());
        log.info("管道单条成本校准完成: cost={}micros ÷ intake={} = {}micros/条", cost, newsCount, perItem);
        return perItem;
    }

    private GuardLevel deriveLevel(long cost, long budget) {
        if (cost >= Math.round(budget * settings.fuseRatio())) {
            return GuardLevel.FUSED;
        }
        if (cost >= Math.round(budget * settings.degradeRatio())) {
            return GuardLevel.DEGRADED;
        }
        return GuardLevel.NORMAL;
    }

    private void publishFusedAlertOnce(long cost, long budget) {
        String episodeKey = LocalDate.ofInstant(clock.instant(), STAT_ZONE).toString();
        // 换日即新 episode（窗口滚动恢复后再次越线可再告警）；内存节流重启重告一次可容忍（ADR-0045）
        if (fusedAlertedEpisodes.putIfAbsent(episodeKey, Boolean.TRUE) != null) {
            return;
        }
        eventPublisher.publishEvent(new PipelineFusedEvent(cost, budget, clock.instant()));
        log.warn("管道成本熔断告警已发布: cost={}micros budget={}micros episode={}", cost, budget, episodeKey);
    }

    private void writeCalibration(long perItemMicros, String dateText) {
        RuntimeConfigEntry existing =
                configService.read(PipelineSettings.KEY_PIPELINE_BUDGET).orElse(null);
        ObjectNode doc = readOrCreateBudgetDoc(existing);
        doc.put("calibratedPerItemMicros", perItemMicros);
        doc.put("costBasis", CALIBRATED_BASIS_PREFIX + dateText);
        try {
            configService.write(
                    PipelineSettings.KEY_PIPELINE_BUDGET,
                    objectMapper.writeValueAsString(doc),
                    existing == null ? null : existing.updatedAt());
        } catch (BusinessException e) {
            // 并发冲突/校验失败：本次校准放弃（次日再校准），旧值继续生效——旁路不阻断批窗口
            log.warn("管道校准写入被拒（旧值继续生效）: {}", e.getMessage());
        } catch (Exception e) {
            log.error("管道校准写入失败: {}", e.toString(), e);
        }
    }

    private ObjectNode readOrCreateBudgetDoc(RuntimeConfigEntry existing) {
        if (existing != null && existing.document() != null && existing.document().isObject()) {
            return (ObjectNode) existing.document().deepCopy();
        }
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("dailyBudgetMicros", PipelineSettings.DEFAULT_DAILY_BUDGET_MICROS);
        doc.put("degradeRatio", PipelineSettings.DEFAULT_DEGRADE_RATIO);
        doc.put("fuseRatio", PipelineSettings.DEFAULT_FUSE_RATIO);
        doc.put("calibratedPerItemMicros", PipelineSettings.DEFAULT_CALIBRATED_PER_ITEM_MICROS);
        doc.put("costBasis", PipelineSettings.DEFAULT_COST_BASIS);
        return doc;
    }

    private Instant todayStartInstant() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        return today.atStartOfDay(STAT_ZONE).toInstant();
    }
}
