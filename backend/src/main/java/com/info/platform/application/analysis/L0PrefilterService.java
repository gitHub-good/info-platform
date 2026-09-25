package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.analysis.NearDuplicateDetector;
import com.info.platform.domain.analysis.NearDuplicateDetector.DupParams;
import com.info.platform.domain.analysis.NearDuplicateDetector.Verdict;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.analysis.NoiseRuleEngine;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L0 规则预筛服务（应用层，M15 T120，方案 §4.2）：noise 规则（广告/推广关键词 + 正则）→ 近重复判定（同窗标题 simhash + 编辑距离） → 批量建 {@code
 * news_analysis} 行（PASS/NOISE/NEAR_DUP + 主条引用）。
 *
 * <p>挂接点 = NEWS_PIPELINE 批窗口内、L1 之前（ADR-0046 裁决 4：L0 随批窗口跑不挂入库同步——近重复需对 24h 池比较天然批式）。 判定顺序固定 noise
 * 先于近重复（噪音条目不参与主条竞争）；PASS 主条即时进入本批比较池（批内同组只留最早主条）。
 */
@Service
public class L0PrefilterService {

    private static final Logger log = LoggerFactory.getLogger(L0PrefilterService.class);

    private final NewsAnalysisRepository repository;
    private final PipelineSettings settings;
    private final Clock clock;

    public L0PrefilterService(
            NewsAnalysisRepository repository, PipelineSettings settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 执行一轮 L0 预筛（空候选静默返回零报告——无新闻不产生留痕噪音）。
     *
     * @return 本轮三态计数（tick 明细数据面）
     */
    public L0Report run() {
        String createdBefore =
                clock.instant().minus(Duration.ofMinutes(settings.l0BufferMinutes())).toString();
        List<NewsAnalysisRepository.NewsCandidate> candidates =
                repository.findUnanalyzed(createdBefore, PipelineSettings.L0_INTAKE_CAP_PER_TICK);
        if (candidates.isEmpty()) {
            return new L0Report(0, 0, 0);
        }
        List<NewsAnalysisRepository.NewsCandidate> pool =
                repository.findPassPoolSince(
                        clock.instant()
                                .minus(Duration.ofHours(settings.nearDupWindowHours()))
                                .toString(),
                        PipelineSettings.NEAR_DUP_POOL_CAP);

        List<NewsAnalysis> rows = buildRows(candidates, pool);
        int inserted = repository.insertIgnoreBatch(rows);
        L0Report report = countReport(rows);
        log.info(
                "L0 预筛完成: 候选={} 实插={}（重入收敛 {}）; {}",
                candidates.size(),
                inserted,
                candidates.size() - inserted,
                report.detail());
        return report;
    }

    /** 两段判定建行：noise 优先剔除 → 剩余条目按时间序过近重复（PASS 即入池参与后续比较）。 */
    private List<NewsAnalysis> buildRows(
            List<NewsAnalysisRepository.NewsCandidate> candidates,
            List<NewsAnalysisRepository.NewsCandidate> pool) {
        NoiseRuleEngine engine = settings.noiseRuleEngine();
        NearDuplicateDetector detector = new NearDuplicateDetector();
        DupParams params = settings.dupParams();

        List<NewsAnalysisRepository.NewsCandidate> survived = new ArrayList<>(candidates.size());
        List<NewsAnalysis> rows = new ArrayList<>(candidates.size());
        for (NewsAnalysisRepository.NewsCandidate candidate : candidates) {
            Optional<String> noiseHit = engine.evaluate(candidate.title(), candidate.summary());
            if (noiseHit.isPresent()) {
                rows.add(
                        NewsAnalysis.newForL0(
                                candidate.newsId(),
                                L0Result.NOISE,
                                null,
                                "noise:" + noiseHit.get()));
                continue;
            }
            survived.add(candidate);
        }
        for (Verdict verdict : detector.evaluate(pool, survived, params)) {
            rows.add(
                    NewsAnalysis.newForL0(
                            verdict.newsId(),
                            verdict.result(),
                            verdict.nearDupOf(),
                            verdict.detail()));
        }
        return rows;
    }

    private static L0Report countReport(List<NewsAnalysis> rows) {
        int pass = 0;
        int noise = 0;
        int nearDup = 0;
        for (NewsAnalysis row : rows) {
            switch (row.getL0Result()) {
                case PASS -> pass++;
                case NOISE -> noise++;
                case NEAR_DUP -> nearDup++;
            }
        }
        return new L0Report(pass, noise, nearDup);
    }

    /** 一轮 L0 计数（JobRunStats 段式明细数据面，ADR-0036 通道）。 */
    public record L0Report(int pass, int noise, int nearDup) {

        public String detail() {
            return "l0=pass:" + pass + "; noise:" + noise + "; near_dup:" + nearDup;
        }
    }
}
