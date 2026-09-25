package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.NewsAnalysisRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    private final L0PrefilterService l0Prefilter;
    private final ClassificationService classificationService;
    private final EventExtractionService eventExtractionService;
    private final NewsAnalysisRepository repository;
    private final PipelineSettings settings;
    private final Clock clock;

    private volatile LastTick lastTick;

    public NewsPipelineService(
            L0PrefilterService l0Prefilter,
            ClassificationService classificationService,
            EventExtractionService eventExtractionService,
            NewsAnalysisRepository repository,
            PipelineSettings settings,
            Clock clock) {
        this.l0Prefilter = l0Prefilter;
        this.classificationService = classificationService;
        this.eventExtractionService = eventExtractionService;
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 执行一轮批窗口（定时与手动触发共用入口；整轮异常不上抛——ManagedJob 惯例）。
     *
     * @return tick 明细与处理计数（JobRunStats 留痕）
     */
    public TickReport tick() {
        Instant startedAt = clock.instant();
        L0Outcome l0 = runL0();
        L1Outcome l1 = runL1();
        L2Outcome l2 = runL2();
        String detail = l0.detail() + "; " + l1.detail() + "; " + l2.detail();
        lastTick = new LastTick(startedAt, clock.instant(), detail);
        return new TickReport(detail, l0.total() + l1.done() + l2.processed());
    }

    /** 最近一轮批窗口（status 端点「最近批窗口」数据面；未跑过为 null）。 */
    public LastTick lastTick() {
        return lastTick;
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
                            since, settings.maxRetriesPerDay(), PipelineSettings.L1_TICK_CAP);
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
            return new L1Outcome(done, failed, pending.size());
        } catch (RuntimeException e) {
            log.error("L1 归类段失败: {}", e.toString(), e);
            return new L1Outcome(0, 0, -1);
        }
    }

    private L2Outcome runL2() {
        try {
            EventExtractionService.L2Report report = eventExtractionService.runL2Window();
            return new L2Outcome(report);
        } catch (RuntimeException e) {
            log.error("L2 事件提取段失败: {}", e.toString(), e);
            return new L2Outcome(null);
        }
    }

    /** tick 报告（JobRunStats）。 */
    public record TickReport(String detail, int processed) {}

    /** 最近批窗口（status 端点数据面）。 */
    public record LastTick(Instant startedAt, Instant finishedAt, String detail) {}

    private record L0Outcome(int total, String detail) {}

    private record L1Outcome(int done, int failed, int pending) {

        private String detail() {
            return pending < 0
                    ? "l1=error"
                    : "l1=done:" + done + "; fail:" + failed + "; pending=" + pending;
        }
    }

    private record L2Outcome(EventExtractionService.L2Report report) {

        private int processed() {
            return report == null ? 0 : report.extracted() + report.noEvent() + report.failed();
        }

        private String detail() {
            return report == null ? "l2=error" : report.detail();
        }
    }
}
