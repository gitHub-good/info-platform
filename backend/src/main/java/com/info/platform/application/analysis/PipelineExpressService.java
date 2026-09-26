package com.info.platform.application.analysis;

import com.info.platform.application.recommendation.RecommendationSettings;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.ImportanceScorer;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.analysis.NoiseRuleEngine;
import com.info.platform.domain.feed.AiExclusion;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 高价值快速通道服务（应用层，M16 T131，ADR-0051 裁决 1 / 方案 §4.3）：FIXED_DELAY 2min tick——「入库 ≥30s 缓冲且未建 analysis
 * 行」的候选经 noise 快判后按 {@link ImportanceScorer} 纯规则预筛分 ≥ expressScoreThreshold（缺省 4.0 ≈ 源权重 2.0 +
 * 一个强触发词 2.0）的条目<b>立即</b> L0 建行（含 T130 序列豁免）→ L1 批量归类（复用 {@link ClassificationService}， 批 ≤10）→ L2
 * 事件提取（复用 {@link EventExtractionService} 当日配额内重扫——express 条目即高分头部天然优先）。
 *
 * <p><b>低于阈值的条目一律不动</b>，等常规 10min 批（NEWS_PIPELINE 零改动，零回归红线）；noise 条目不进快速通道建行
 * （避免噪音词误配强触发，交常规批）。护栏联动同常规语义：FUSED 全跳（留痕 skip）；DEGRADED 跳 L2 保 L1。
 *
 * <p><b>与常规批的并发幂等</b>（裁决 1）：双 Job 可能同刻拾取同一 news_id——{@code news_analysis.UNIQUE(news_id)} INSERT OR
 * IGNORE 建行、L1/L2 条件 UPDATE（{@code WHERE l1_status/l2_status IN (PENDING/FAILED, ...)}）+ SQLite
 * 单写者串行化， 双跑结果幂等（后到者条件更新匹配 0 行自然跳过）。
 */
@Service
public class PipelineExpressService {

    private static final Logger log = LoggerFactory.getLogger(PipelineExpressService.class);

    /** express 入库缓冲（方案 §3.1：30s——与摄取事务竞态的错峰）。 */
    static final Duration EXPRESS_BUFFER = Duration.ofSeconds(30);

    private final NewsAnalysisRepository repository;

    private final L0PrefilterService l0Prefilter;

    private final ClassificationService classificationService;

    private final EventExtractionService eventExtractionService;

    private final SubjectMatcher subjectMatcher;

    private final AiExclusionResolver exclusionResolver;

    private final PipelineGuardService guardService;

    private final PipelineSettings pipelineSettings;

    private final RecommendationSettings recommendationSettings;

    private final Clock clock;

    public PipelineExpressService(
            NewsAnalysisRepository repository,
            L0PrefilterService l0Prefilter,
            ClassificationService classificationService,
            EventExtractionService eventExtractionService,
            SubjectMatcher subjectMatcher,
            AiExclusionResolver exclusionResolver,
            PipelineGuardService guardService,
            PipelineSettings pipelineSettings,
            RecommendationSettings recommendationSettings,
            Clock clock) {
        this.repository = repository;
        this.l0Prefilter = l0Prefilter;
        this.classificationService = classificationService;
        this.eventExtractionService = eventExtractionService;
        this.subjectMatcher = subjectMatcher;
        this.exclusionResolver = exclusionResolver;
        this.guardService = guardService;
        this.pipelineSettings = pipelineSettings;
        this.recommendationSettings = recommendationSettings;
        this.clock = clock;
    }

    /**
     * 执行一轮快速通道（整轮异常不上抛——ManagedJob 惯例；JobRunStats 明细 express=scan:n; hit:n; ...）。
     *
     * @return tick 报告（JobRunStats 留痕）
     */
    public ExpressReport tick() {
        GuardLevel level = guardService.currentLevel();
        if (level == GuardLevel.FUSED) {
            return new ExpressReport("express=skip(fused)", 0);
        }
        List<NewsAnalysisRepository.NewsCandidate> candidates =
                repository.findUnanalyzed(
                        clock.instant().minus(EXPRESS_BUFFER).toString(),
                        exclusionResolver.excludedSourceIds(AiExclusion.ALL),
                        RecommendationSettings.EXPRESS_SCAN_CAP);
        ScanOutcome scan = selectHits(candidates);
        if (scan.hits().isEmpty()) {
            return new ExpressReport("express=scan:" + candidates.size() + "; hit:0", 0);
        }

        // L0 建行（复用近重复判定 + 序列豁免；与常规批并发靠 UNIQUE(news_id) INSERT OR IGNORE 幂等）
        List<NewsAnalysisRepository.NewsCandidate> pool =
                repository.findPassPoolSince(
                        clock.instant()
                                .minus(Duration.ofHours(pipelineSettings.nearDupWindowHours()))
                                .toString(),
                        PipelineSettings.NEAR_DUP_POOL_CAP);
        List<NewsAnalysis> rows = l0Prefilter.buildRowsForSurvived(scan.hits(), pool);
        int inserted = repository.insertIgnoreBatch(rows);
        repository.updateImportanceScores(scoresOfPassRows(rows, scan.scores()));

        // L1：本批 PASS 行（候选取数后按本批 id 过滤——只归类 express 本 tick 建行的条目）
        L1Outcome l1 = classifyExpressRows(rows, level);
        // L2：当日 SELECTED 重扫（NORMAL 才跑；express 条目即高分头部天然优先出事件、计入当日配额）
        L2Outcome l2 = runL2(level);
        String detail =
                "express=scan:"
                        + candidates.size()
                        + "; hit:"
                        + scan.hits().size()
                        + "; l0="
                        + inserted
                        + "; "
                        + l1.detail()
                        + "; "
                        + l2.detail();
        log.info("快速通道 tick 完成: {}", detail);
        return new ExpressReport(detail, inserted + l1.done() + l2.processed());
    }

    /** 预筛分：noise 快判（交常规批）→ ImportanceScorer 纯规则打分 ≥ 阈值者命中。 */
    private ScanOutcome selectHits(List<NewsAnalysisRepository.NewsCandidate> candidates) {
        NoiseRuleEngine engine = pipelineSettings.noiseRuleEngine();
        ImportanceScorer.ScorerParams params = pipelineSettings.l2ScorerParams();
        double threshold = recommendationSettings.expressScoreThreshold();
        List<NewsAnalysisRepository.NewsCandidate> hits = new ArrayList<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (NewsAnalysisRepository.NewsCandidate candidate : candidates) {
            if (engine.evaluate(candidate.title(), candidate.summary()).isPresent()) {
                continue; // noise → 常规批（不在快速通道建行）
            }
            boolean subjectMatched =
                    !subjectMatcher.match(candidate.title(), candidate.summary()).isEmpty();
            double score =
                    ImportanceScorer.score(
                            candidate.title(),
                            candidate.summary(),
                            candidate.sourceCategory(),
                            subjectMatched,
                            params);
            if (score >= threshold) {
                hits.add(candidate);
                scores.put(candidate.newsId(), score);
            }
        }
        return new ScanOutcome(hits, scores);
    }

    /** express L1：候选取数（24h 窗口）∩ 本批 PASS 建行 id，按 expressBatchSize 分批归类（复用批量/拆批/兜底）。 */
    private L1Outcome classifyExpressRows(List<NewsAnalysis> rows, GuardLevel level) {
        Set<Long> insertedPassIds = new LinkedHashSet<>();
        for (NewsAnalysis row : rows) {
            if (row.getL0Result() == com.info.platform.domain.analysis.L0Result.PASS) {
                insertedPassIds.add(row.getNewsId());
            }
        }
        if (insertedPassIds.isEmpty()) {
            return new L1Outcome(0, 0, "l1=0");
        }
        try {
            String since =
                    clock.instant()
                            .minus(Duration.ofHours(pipelineSettings.l1BackfillHours()))
                            .toString();
            List<NewsAnalysisRepository.ClassificationCandidate> pending =
                    repository.findPendingForL1(
                            since,
                            pipelineSettings.maxRetriesPerDay(),
                            exclusionResolver.excludedSourceIds(AiExclusion.ALL),
                            PipelineSettings.L1_TICK_CAP);
            List<NewsAnalysisRepository.ClassificationCandidate> batch = new ArrayList<>();
            for (NewsAnalysisRepository.ClassificationCandidate candidate : pending) {
                if (insertedPassIds.contains(candidate.newsId())) {
                    batch.add(candidate);
                }
            }
            int batchSize = recommendationSettings.expressBatchSize();
            int done = 0;
            int failed = 0;
            for (int from = 0; from < batch.size(); from += batchSize) {
                ClassificationService.BatchOutcome outcome =
                        classificationService.classifyBatch(
                                batch.subList(from, Math.min(from + batchSize, batch.size())));
                done += outcome.done();
                failed += outcome.failed();
            }
            return new L1Outcome(done, failed, "l1=" + done + "; fail:" + failed);
        } catch (RuntimeException e) {
            log.error("express L1 段失败: {}", e.toString(), e);
            return new L1Outcome(0, 0, "l1=error");
        }
    }

    private L2Outcome runL2(GuardLevel level) {
        if (level != GuardLevel.NORMAL) {
            return new L2Outcome(0, "l2=skip(degraded)"); // DEGRADED 跳 L2（同常规语义）
        }
        try {
            EventExtractionService.L2Report report = eventExtractionService.runL2Window();
            int processed = report.extracted() + report.noEvent() + report.failed();
            return new L2Outcome(processed, "l2=" + processed);
        } catch (RuntimeException e) {
            log.error("express L2 段失败: {}", e.toString(), e);
            return new L2Outcome(0, "l2=error");
        }
    }

    /** 本批 PASS 建行的重要性分快照（importance_score 落库，方案 §4.3）。 */
    private static Map<Long, Double> scoresOfPassRows(
            List<NewsAnalysis> rows, Map<Long, Double> scores) {
        Map<Long, Double> passScores = new LinkedHashMap<>();
        for (NewsAnalysis row : rows) {
            if (row.getL0Result() == com.info.platform.domain.analysis.L0Result.PASS
                    && scores.containsKey(row.getNewsId())) {
                passScores.put(row.getNewsId(), scores.get(row.getNewsId()));
            }
        }
        return passScores;
    }

    /** tick 报告（JobRunStats 留痕）。 */
    public record ExpressReport(String detail, int processed) {}

    private record ScanOutcome(
            List<NewsAnalysisRepository.NewsCandidate> hits, Map<Long, Double> scores) {}

    private record L1Outcome(int done, int failed, String detail) {}

    private record L2Outcome(int processed, String detail) {}
}
