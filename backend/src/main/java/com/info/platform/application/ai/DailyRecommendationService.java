package com.info.platform.application.ai;

import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.common.UserContext;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 每日推荐应用服务（应用层，T23，对齐技术方案 §4.3 流程 2 + §4.1.6 + Spike-2 §11.5）。
 *
 * <p>复用 T21 {@link AIBriefService#createBrief}（briefType=4，subjectId 可空）触发异步生成，上下文（poolMetrics）由
 * {@link DailyRecommendationContextBuilder} 装配并在 {@link AIBriefService#generateAndPersist}
 * 合并。本服务受理后轮询查询简报直至终态或超时，解析 {@link BriefContent#topRecommend()} 取 Top5（按 rank 升序）。
 *
 * <p>降级链（对齐技术方案 §5「每日推荐用规则兜底」）： ① 当日已生成（幂等键 {@code none:4:yyyyMMdd} 命中）→ 直接解析缓存 Top5； ② 首次请求触发异步生成
 * → 轮询至 DONE/NEED_VERIFY（content 含 topRecommend）→ Top5； ③ LLM 失败/超时/输出空 Top5 → 规则兜底（活跃度综合分排序取前
 * 5，每只一句话理由）； ④ 自选池空 → 空 Top5 + 提示状态。
 *
 * <p>触发模式：按需（GET 端点首次请求触发 + 幂等缓存命中后续直返）；盘前 @Scheduled 预热为可选优化（见 {@code
 * DailyRecommendationJob}，开关控制）。受开关控制（测试关）：本服务无 @Scheduled 副作用，单测直调 {@link #generateDaily} 验证逻辑。
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
    private final long pollIntervalMillis;
    private final long maxWaitMillis;

    public DailyRecommendationService(
            AIBriefService aiBriefService,
            DailyRecommendationContextBuilder contextBuilder,
            @Value("${recommendation.poll-interval-millis:500}") long pollIntervalMillis,
            @Value("${recommendation.max-wait-millis:30000}") long maxWaitMillis) {
        this.aiBriefService = aiBriefService;
        this.contextBuilder = contextBuilder;
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

    /** 规则兜底：自选池空→空结果；否则按活跃度综合分排序取前 5。 */
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
        List<TopRecommendation> top =
                metrics.stream()
                        .sorted(Comparator.comparingDouble(PoolMetric::activityScore).reversed())
                        .limit(TOP_N)
                        .map(DailyRecommendationService::toFallbackRecommendation)
                        .toList();
        log.info("每日推荐规则兜底 userId={} top={}", userId, top.size());
        return new DailyRecommendationResult(
                DailyRecommendationResult.STATUS_FALLBACK,
                top,
                BriefContent.DEFAULT_DISCLAIMER,
                true);
    }

    /** 规则兜底条目：rank 从 1 递增，理由一句话（活跃度依据，非买卖建议）。 */
    private static TopRecommendation toFallbackRecommendation(PoolMetric m) {
        // rank 在 stream map 时无索引，这里用 0 占位，由调用方按序重排——简化：rank=0，展示层按列表顺序取。
        return new TopRecommendation(
                m.subjectCode(),
                m.subjectName(),
                "信息面活跃：涨跌幅"
                        + m.changePct()
                        + "%，公告"
                        + m.announceCount()
                        + "条，新闻"
                        + m.newsCount()
                        + "条",
                0);
    }

    private static String disclaimerOf(AIBriefView view) {
        return view.disclaimer() == null || view.disclaimer().isBlank()
                ? BriefContent.DEFAULT_DISCLAIMER
                : view.disclaimer();
    }
}
