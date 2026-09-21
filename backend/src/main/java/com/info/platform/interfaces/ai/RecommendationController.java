package com.info.platform.interfaces.ai;

import com.info.platform.application.ai.DailyRecommendationResult;
import com.info.platform.application.ai.DailyRecommendationService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日推荐接口（对齐技术方案 §4.1.6）。
 *
 * <p>{@code GET /api/v1/recommendations/daily}（Bearer）：返回当日盘前 Top5 推荐（每只
 * subjectCode/subjectName/reason/rank）。 受 JWT 保护（T17 {@code JwtAuthFilter} 写入 {@link
 * UserContext}），服务层取当前用户自选池装配上下文。当日已生成则命中幂等缓存直返（复用 T21 {@code none:4:yyyyMMdd} 幂等键）。全部响应恒附免责声明「AI
 * 生成，非投资建议」。
 *
 * <p>响应 {@code status}： {@code 1}=AI 生成 Top5； {@code 2}=规则兜底（LLM 失败/超时/空输出时活跃度排序，对齐 §5 降级预案）；
 * {@code 3}=自选池空。
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

    private final DailyRecommendationService dailyRecommendationService;

    public RecommendationController(DailyRecommendationService dailyRecommendationService) {
        this.dailyRecommendationService = dailyRecommendationService;
    }

    /**
     * 每日推荐 Top5。
     *
     * @return 200 + {status, topRecommend[], disclaimer}
     */
    @GetMapping("/daily")
    public Result<DailyRecommendationView> daily() {
        long userId = currentUserId();
        log.debug("每日推荐请求 userId={}", userId);
        DailyRecommendationResult result = dailyRecommendationService.generateDaily(userId);
        return Result.ok(
                new DailyRecommendationView(
                        result.status(), result.topRecommend(), result.disclaimer()));
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }
}
