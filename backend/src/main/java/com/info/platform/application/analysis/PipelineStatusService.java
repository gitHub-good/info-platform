package com.info.platform.application.analysis;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 管道状态服务（应用层，M15 T121 基础版，方案 §4.8）：当日三态计数 + 最近批窗口 + 当日 L1 成本（scene=5 口径）。
 *
 * <p>日界 = Asia/Shanghai（统计语义优先本地日，方案 §8 风险表口径；V22 source_daily_stats 先例）；护栏完整版（level 派生/预算面/ SLA
 * 口径）随 T125 扩展。成本读取 llm_call_log 上限护栏（个人量级日 ~40 行，上限 10000 防御）。
 */
@Service
public class PipelineStatusService {

    /** 统计日界（Asia/Shanghai——与 source_daily_stats.stat_date 同口径）。 */
    static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** briefType=5（行业归类）scene 键——llm_call_log 成本口径。 */
    static final String SCENE_L1_CLASSIFY = "5";

    private static final int COST_LOG_LIMIT = 10_000;

    private final NewsAnalysisRepository repository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final NewsPipelineService pipelineService;
    private final Clock clock;

    public PipelineStatusService(
            NewsAnalysisRepository repository,
            LlmCallLogRepository llmCallLogRepository,
            NewsPipelineService pipelineService,
            Clock clock) {
        this.repository = repository;
        this.llmCallLogRepository = llmCallLogRepository;
        this.pipelineService = pipelineService;
        this.clock = clock;
    }

    /** 当前管道状态（基础版）。 */
    public PipelineStatusView status() {
        String todayStart = todayStartIso();
        return new PipelineStatusView(
                "NEWS_PIPELINE",
                todayCounts(todayStart),
                PipelineStatusView.LastTickView.of(pipelineService.lastTick()),
                todayCostMicros(todayStart));
    }

    private String todayStartIso() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
        return today.atStartOfDay(STAT_ZONE).toInstant().toString();
    }

    private PipelineStatusView.TodayView todayCounts(String todayStartIso) {
        Map<String, Long> l0 = repository.countL0ByResultSince(todayStartIso);
        Map<String, Long> l1 = repository.countL1ByStatusSince(todayStartIso);
        return new PipelineStatusView.TodayView(
                countOf(l0, "PASS"),
                countOf(l0, "NOISE"),
                countOf(l0, "NEAR_DUP"),
                countOf(l1, "DONE"),
                countOf(l1, "PENDING"),
                countOf(l1, "FAILED"));
    }

    private long todayCostMicros(String todayStartIso) {
        List<LlmCallLog> logs =
                llmCallLogRepository.findCreatedSince(Instant.parse(todayStartIso), COST_LOG_LIMIT);
        return logs.stream()
                .filter(log -> SCENE_L1_CLASSIFY.equals(log.getSceneKey()))
                .filter(log -> log.getStatus() == LlmCallStatus.SUCCESS)
                .mapToLong(LlmCallLog::getCostMicros)
                .sum();
    }

    private static long countOf(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }
}
