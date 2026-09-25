package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AiExclusion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管道 tick 编排（应用层，M15 T121/T122，方案 §4.3/§4.4/§4.7）：L0 预筛 → L1 批量归类 → L2 配额提取（三段顺序、段间独立容错）。
 *
 * <p><b>段间独立容错</b>（ADR-0046 裁决 4）：一段失败不阻断后段与下轮——各段 try-catch 记 ERROR，段级结果进 tick 明细。 tick 明细为
 * JobRunStats 段式串（ADR-0036 通道）：{@code l0=pass:n; noise:n; near_dup:n; l1=done:n; fail:n; pending=n;
 * l2=extracted:n; no_event:n; failed:n; deferred:n}。
 *
 * <p>{@code lastTick} 为 {@code GET /pipeline/status} 的「最近批窗口」数据面（护栏挂钩随 T125 追加：DEGRADED 跳 L2 /
 * FUSED 全跳）。
 */
@Service
public class NewsPipelineService {

    private static final Logger log = LoggerFactory.getLogger(NewsPipelineService.class);

    /** 校准评估日界（Asia/Shanghai——status/护栏同口径）。 */
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 单条成本校准触发时点（每日 ≥23 点的首 tick——当日数据近全量）。 */
    private static final int CALIBRATE_HOUR = 23;

    private final L0PrefilterService l0Prefilter;
    private final ClassificationService classificationService;
    private final EventExtractionService eventExtractionService;
    private final NewsAnalysisRepository repository;
    private final PipelineSettings settings;
    private final PipelineGuardService guardService;
    private final AiExclusionResolver exclusionResolver; // T125：aiExclusion=ALL 的 L1 排除面
    private final Clock clock;

    private volatile LastTick lastTick;

    /** 当日已触发校准标记（内存去重；重启重跑为幂等覆盖可容忍）。 */
    private volatile LocalDate lastCalibratedDate;

    public NewsPipelineService(
            L0PrefilterService l0Prefilter,
            ClassificationService classificationService,
            EventExtractionService eventExtractionService,
            NewsAnalysisRepository repository,
            PipelineSettings settings,
            PipelineGuardService guardService,
            AiExclusionResolver exclusionResolver,
            Clock clock) {
        this.l0Prefilter = l0Prefilter;
        this.classificationService = classificationService;
        this.eventExtractionService = eventExtractionService;
        this.repository = repository;
        this.settings = settings;
        this.guardService = guardService;
        this.exclusionResolver = exclusionResolver;
        this.clock = clock;
    }

    /**
     * 执行一轮批窗口（定时与手动触发共用入口；整轮异常不上抛——ManagedJob 惯例）。
     *
     * <p>护栏挂钩（T125，方案 §4.6）：DEGRADED 跳 L2（保 L1 与日报）；FUSED 连 L1 也跳（L0 零成本照常）；段级留痕 {@code
     * skipL2=1(degraded)} / {@code l1=skip(fused)}。
     *
     * @return tick 明细与处理计数（JobRunStats 留痕）
     */
    public TickReport tick() {
        Instant startedAt = clock.instant();
        GuardLevel level = guardService.currentLevel();
        L0Outcome l0 = runL0();
        L1Outcome l1 = level == GuardLevel.FUSED ? L1Outcome.skippedFused() : runL1();
        L2Outcome l2 = level == GuardLevel.NORMAL ? runL2() : L2Outcome.skipped(level);
        maybeCalibrate();
        String detail = l0.detail() + "; " + l1.detail() + "; " + l2.detail();
        lastTick = new LastTick(startedAt, clock.instant(), detail);
        return new TickReport(detail, l0.total() + l1.done() + l2.processed());
    }

    /** 最近一轮批窗口（status 端点「最近批窗口」数据面；未跑过为 null）。 */
    public LastTick lastTick() {
        return lastTick;
    }

    /** 单条成本校准触发（REQ 拍板四-4「首日全级运行后」）：每日上海 23:00 后的首个 tick 按当日均值写入（内存去重当日一次； 重启重跑一次为幂等覆盖，可容忍）。 */
    private void maybeCalibrate() {
        try {
            LocalDate today = LocalDate.ofInstant(clock.instant(), STAT_ZONE);
            if (clock.instant().atZone(STAT_ZONE).getHour() < CALIBRATE_HOUR
                    || today.equals(lastCalibratedDate)) {
                return;
            }
            guardService.calibratePerItemCost();
            lastCalibratedDate = today;
        } catch (RuntimeException e) {
            log.warn("单条成本校准异常（旁路不影响批窗口）: {}", e.toString());
        }
    }

    private L0Outcome runL0() {
        try {
            L0PrefilterService.L0Report report = l0Prefilter.run();
            return new L0Outcome(
                    report.pass() + report.noise() + report.nearDup(), report.detail());
        } catch (RuntimeException e) {
            log.error("L0 预筛段失败（本段跳过，L1 照常）: {}", e.toString(), e);
            return new L0Outcome(0, "l0=error");
        }
    }

    private L1Outcome runL1() {
        try {
            String since =
                    clock.instant().minus(Duration.ofHours(settings.l1BackfillHours())).toString();
            List<NewsAnalysisRepository.ClassificationCandidate> pending =
                    repository.findPendingForL1(
                            since,
                            settings.maxRetriesPerDay(),
                            exclusionResolver.excludedSourceIds(AiExclusion.ALL),
                            PipelineSettings.L1_TICK_CAP);
            int batchSize = settings.l1BatchSize();
            int done = 0;
            int failed = 0;
            for (int from = 0; from < pending.size(); from += batchSize) {
                ClassificationService.BatchOutcome outcome =
                        classificationService.classifyBatch(
                                pending.subList(from, Math.min(from + batchSize, pending.size())));
                done += outcome.done();
                failed += outcome.failed();
            }
            return new L1Outcome(done, failed, pending.size(), null);
        } catch (RuntimeException e) {
            log.error("L1 归类段失败: {}", e.toString(), e);
            return new L1Outcome(0, 0, -1, null);
        }
    }

    private L2Outcome runL2() {
        try {
            EventExtractionService.L2Report report = eventExtractionService.runL2Window();
            return new L2Outcome(report, null);
        } catch (RuntimeException e) {
            log.error("L2 事件提取段失败: {}", e.toString(), e);
            return new L2Outcome(null, null);
        }
    }

    /** tick 报告（JobRunStats）。 */
    public record TickReport(String detail, int processed) {}

    /** 最近批窗口（status 端点数据面）。 */
    public record LastTick(Instant startedAt, Instant finishedAt, String detail) {}

    private record L0Outcome(int total, String detail) {}

    /** L1 段结果（overrideDetail 非空 = 护栏跳过态留痕，正常路径 null）。 */
    private record L1Outcome(int done, int failed, int pending, String overrideDetail) {

        private String detail() {
            if (overrideDetail != null) {
                return overrideDetail;
            }
            return pending < 0
                    ? "l1=error"
                    : "l1=done:" + done + "; fail:" + failed + "; pending=" + pending;
        }

        static L1Outcome skippedFused() {
            return new L1Outcome(0, 0, 0, "l1=skip(fused)");
        }
    }

    /** L2 段结果（overrideDetail 非空 = 护栏跳过态留痕，正常路径 null）。 */
    private record L2Outcome(EventExtractionService.L2Report report, String overrideDetail) {

        private int processed() {
            return report == null ? 0 : report.extracted() + report.noEvent() + report.failed();
        }

        private String detail() {
            return overrideDetail != null
                    ? overrideDetail
                    : (report == null ? "l2=error" : report.detail());
        }

        /** DEGRADED：跳 L2 保 L1（skipL2 留痕，方案 §4.6）；FUSED：连 L2 一并停。 */
        static L2Outcome skipped(GuardLevel level) {
            return new L2Outcome(
                    null, level == GuardLevel.FUSED ? "l2=skip(fused)" : "skipL2=1(degraded)");
        }
    }
}
