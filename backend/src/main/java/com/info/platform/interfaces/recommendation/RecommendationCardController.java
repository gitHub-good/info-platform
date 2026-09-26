package com.info.platform.interfaces.recommendation;

import com.info.platform.application.recommendation.RecommendationCardDetailView;
import com.info.platform.application.recommendation.RecommendationCardListView;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐中心接口（M16 T133，方案 §4.8，Bearer JWT；feedback/read/stats 三端点随 T134 落地）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/recommendations?level=&eventType=&direction=&read=&beforeId=&limit=} ——
 *       卡片流（id DESC 游标分页；四维筛选可空，非法值/limit 越界 → 30082 字段级 400）
 *   <li>{@code GET /api/v1/recommendations/{id}} —— 卡片详情（logicInputs 抽检面；不存在/非本人 → 30080）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationCardController {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCardController.class);

    private final RecommendationQueryService queryService;

    public RecommendationCardController(RecommendationQueryService queryService) {
        this.queryService = queryService;
    }

    /** 卡片流分页（四维可空筛选 + beforeId 游标 + limit 缺省 20 ≤50）。 */
    @GetMapping
    public Result<RecommendationCardListView> list(
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "read", required = false) String read,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(
                queryService.list(
                        currentUserId(), level, eventType, direction, read, beforeId, limit));
    }

    /** 卡片详情（逻辑链/logic_inputs 快照/标的区可跳转）。 */
    @GetMapping("/{id}")
    public Result<RecommendationCardDetailView> detail(@PathVariable("id") long id) {
        return Result.ok(queryService.detail(currentUserId(), id));
    }

    /** 受保护端点经 JwtAuthFilter 已写入 UserContext；防御性 fail-fast（对齐通知中心惯例）。 */
    private static long currentUserId() {
        UserContext.Principal principal = UserContext.get();
        if (principal == null) {
            log.warn("推荐中心端点缺少认证上下文（UserContext 未写入）");
            throw new IllegalStateException("认证上下文缺失");
        }
        return principal.userId();
    }
}
