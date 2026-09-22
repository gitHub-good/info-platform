package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.common.UserContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 每日推荐应用服务（应用层，T23，对齐技术方案 §4.3 流程 2 + §4.1.6 + Spike-2 §11.5；T29 相关性优化）。
 *
 * <p>复用 T21 {@link AIBriefService#createBrief}（briefType=4，subjectId 可空）触发异步生成，上下文（poolMetrics +
 * T29 个性化画像）由 {@link DailyRecommendationContextBuilder} 装配并在 {@link
 * AIBriefService#generateAndPersist} 合并。本服务受理后轮询查询简报直至终态或超时，解析 {@link BriefContent#topRecommend()}
 * 取 Top5（按 rank 升序）。
 *
 * <p>降级链（对齐技术方案 §5「每日推荐用规则兜底」）： ① 当日已生成（幂等键 {@code none:4:yyyyMMdd} 命中）→ 直接解析缓存 Top5； ② 首次请求触发异步生成
 * → 轮询至 DONE/NEED_VERIFY（content 含 topRecommend）→ Top5； ③ LLM 失败/超时/输出空 Top5 → 规则兜底（T29
 * 相关性综合分排序：活跃度 + 标的订阅 + 订阅主题命中 + 已读热度，理由逐因子列明）； ④ 自选池空 → 空 Top5 + 提示状态。
 *
 * <p>T29 相关性（PRD 场景 2 命中率 ≥70% 抽样标注口径）：兜底排序经 {@link RecommendationPersonalizer} 装配用户画像 （订阅 + 近 30
 * 天阅读）、{@link RecommendationRelevanceScorer} 评分——推荐理由带评分构成与命中因子（命中哪个主题/已读标的），
 * 前端展示后可逐条人工标注。空画像（新用户）退化为既有活跃度排序，行为不变。
 *
 * <p>触发模式：按需（GET 端点首次请求触发 + 幂等缓存命中后续直返）；盘前预热为可选优化（见 {@code DailyRecommendationJob}，T37 后按运行时配置启停，测试
 * profile 种子停用零注册）：本服务无调度副作用，单测直调 {@link #generateDaily} 验证逻辑。
 *
 * <p>幂等说明：复用 T21 幂等键 {@code subjectId(null→"none")+briefType(4)+date}，当日全局唯一（ai_brief 无 user_id
 * 列，对齐 §4.2 DDL）。 个人/小团队量级单用户场景下幂等缓存正确；多用户共享当日简报为已知限制（见自测报告遗留风险）。
 */
@Service
public class DailyRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(DailyRecommendationService.class);

    private static final int TOP_N = 5;

    private final AIBriefService aiBriefService;
    private final DailyRecommendationContextBuilder contextBuilder;
    private final RecommendationPersonalizer personalizer;
    private final RecommendationRelevanceScorer relevanceScorer;
    private final long pollIntervalMillis;
    private final long maxWaitMillis;

    public DailyRecommendationService(
            AIBriefService aiBriefService,
            DailyRecommendationContextBuilder contextBuilder,
            RecommendationPersonalizer personalizer,
            RecommendationRelevanceScorer relevanceScorer,
            @Value("${recommendation.poll-interval-millis:500}") long pollIntervalMillis,
            @Value("${recommendation.max-wait-millis:30000}") long maxWaitMillis) {
        this.aiBriefService = aiBriefService;
        this.contextBuilder = contextBuilder;
        this.personalizer = personalizer;
        this.relevanceScorer = relevanceScorer;
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * 生成每日推荐 Top5（GET /recommendations/daily 调用）。
     *
     * <p>受理（幂等）→ 轮询至终态/超时 → 解析 Top5 / 规则兜底 / 空池提示。UserContext 由本方法设置（createBrief 与成本预检取数依赖），finally
     * 清空防线程池复用串味。
     *
     * @param userId 归属用户（自选池行级权限取数键）
     * @return 生成结果（status=1 AI / 2 兜底 / 3 空池）
     */
    public DailyRecommendationResult generateDaily(long userId) {
        UserContext.set(new UserContext.Principal(userId, "daily-recommend"));
        try {
            Long taskId = aiBriefService.createBrief(null, BriefType.DAILY_RECOMMEND);
            AIBriefView view = pollUntilTerminal(taskId, userId);
            List<TopRecommendation> aiTop = extractTop(view);
            if (!aiTop.isEmpty()) {
                String disclaimer = disclaimerOf(view);
                log.info("每日推荐 AI 生成成功 userId={} taskId={} top={}", userId, taskId, aiTop.size());
                return new DailyRecommendationResult(
                        DailyRecommendationResult.STATUS_DONE, aiTop, disclaimer, false);
            }
            return ruleFallback(userId, view);
        } finally {
            UserContext.clear();
        }
    }

    /** 轮询查询简报直至终态（DONE/FAILED/NEED_VERIFY）或超时。 */
    private AIBriefView pollUntilTerminal(Long taskId, long userId) {
        long deadline = System.nanoTime() + maxWaitMillis * 1_000_000L;
        AIBriefView view = aiBriefService.getBrief(taskId);
        while (view.status() == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("每日推荐轮询被中断 userId={} taskId={}", userId, taskId);
                break;
            }
            view = aiBriefService.getBrief(taskId);
        }
        return view;
    }

    /** 从简报视图提取 Top5（按 rank 升序，截断 5）。content 为空或 topRecommend 空时返回空列表。 */
    private static List<TopRecommendation> extractTop(AIBriefView view) {
        BriefContent content = view.content();
        if (content == null || content.topRecommend().isEmpty()) {
            return List.of();
        }
        return content.topRecommend().stream()
                .sorted(Comparator.comparingInt(TopRecommendation::rank))
                .limit(TOP_N)
                .toList();
    }

    /**
     * 规则兜底（T29 相关性排序）：自选池空→空结果；否则装配用户画像（订阅+阅读）→ 相关性综合分排序取前 5， 理由逐因子列明（命中哪个主题/已读标的 +
     * 评分构成）。空画像退化为既有活跃度排序（新用户回退）。
     */
    private DailyRecommendationResult ruleFallback(long userId, AIBriefView view) {
        List<PoolMetric> metrics = contextBuilder.buildPoolMetrics(userId);
        if (metrics.isEmpty()) {
            log.info("每日推荐自选池为空 userId={}", userId);
            return new DailyRecommendationResult(
                    DailyRecommendationResult.STATUS_EMPTY,
                    List.of(),
                    BriefContent.DEFAULT_DISCLAIMER,
                    false);
        }
        UserInterestProfile profile = personalizer.buildProfile(userId);
        List<RecommendationRelevanceScorer.ScoredSubject> picked =
                relevanceScorer.score(metrics, profile).stream()
                        .sorted(FALLBACK_ORDER)
                        .limit(TOP_N)
                        .toList();
        List<TopRecommendation> top = new ArrayList<>(picked.size());
        for (int i = 0; i < picked.size(); i++) {
            top.add(toFallbackRecommendation(picked.get(i), i + 1));
        }
        log.info(
                "每日推荐规则兜底 userId={} top={} personalized={}",
                userId,
                top.size(),
                profile.isPersonalized());
        return new DailyRecommendationResult(
                DailyRecommendationResult.STATUS_FALLBACK,
                top,
                BriefContent.DEFAULT_DISCLAIMER,
                true);
    }

    /** 兜底排序：相关性综合分降序，平分按代码升序（稳定输出，便于标注复核）。 */
    private static final Comparator<RecommendationRelevanceScorer.ScoredSubject> FALLBACK_ORDER =
            Comparator.comparingDouble(
                            (RecommendationRelevanceScorer.ScoredSubject s) -> s.relevanceScore())
                    .reversed()
                    .thenComparing(s -> s.metric().subjectCode());

    /** 规则兜底条目：rank 按排序位次 1 起递增（对齐 DailyRecommendationResult javadoc），理由为评分器产出的可解释理由。 */
    private static TopRecommendation toFallbackRecommendation(
            RecommendationRelevanceScorer.ScoredSubject scored, int rank) {
        return new TopRecommendation(
                scored.metric().subjectCode(),
                scored.metric().subjectName(),
                scored.reason(),
                rank);
    }

    private static String disclaimerOf(AIBriefView view) {
        return view.disclaimer() == null || view.disclaimer().isBlank()
                ? BriefContent.DEFAULT_DISCLAIMER
                : view.disclaimer();
    }
}
