package com.info.platform.interfaces.recommendation;

import com.info.platform.application.recommendation.RecommendationCardDetailView;
import com.info.platform.application.recommendation.RecommendationFeedbackService;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.application.recommendation.RecommendationStatsView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐中心接口（M16 T133 + T134，方案 §4.8，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/recommendations?level=&eventType=&direction=&read=&beforeId=&limit=} ——
 *       卡片流（id DESC 游标分页；四维筛选可空，非法值/limit 越界 → 30082 字段级 400）
 *   <li>{@code GET /api/v1/recommendations?level=&...&page=&size=} —— <b>页码模式</b>（M25 T220，V3.0
 *       REQ-20260928-21 拍板四，M9 PageQuery 双模式同端点分派）：{@code page} 参数出现即页码模式（{items, total, page,
 *       size}，offset 语义，越界页 200 + 空列表）；缺席走游标路径（字节级不动）。页码模式参数校验经 {@link
 *       com.info.platform.interfaces.common.PageQuery} 共用件（{@code 2001 PARAM_INVALID} 段）：{@code
 *       page≥1}（上限 1,000,000 防 offset 溢出）、{@code size} 缺省 20、1~50 越界 400 拒绝不截断、{@code page} 与
 *       {@code beforeId} 互斥 400、{@code limit} 仅游标模式可用（页码模式请用 size）。四维筛选两模式均可用。
 *   <li>{@code GET /api/v1/recommendations/{id}} —— 卡片详情（logicInputs 抽检面；不存在/非本人 → 30080）
 *   <li>{@code POST /api/v1/recommendations/{id}/feedback} ——
 *       四动作反馈（USEFUL/DISLIKE/ADD_WATCHLIST/UNDO_MUTE； 响应 {muteUntil?, escalated?}；卡片不存在/非本人 →
 *       30080，action 非法或 subjectCode 缺失 → 30081）
 *   <li>{@code POST /api/v1/recommendations/{id}/read} —— 已读 + 隐式采纳（幂等 200 直返；不存在 → 30080）
 *   <li>{@code GET /api/v1/recommendations/stats?date=} —— 采纳统计 adopt-v1（缺省当日；日期非法 → 30082）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationCardController {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCardController.class);

    private final RecommendationQueryService queryService;

    private final RecommendationFeedbackService feedbackService;

    public RecommendationCardController(
            RecommendationQueryService queryService,
            RecommendationFeedbackService feedbackService) {
        this.queryService = queryService;
        this.feedbackService = feedbackService;
    }

    /**
     * 卡片流双模式分页：page 出现即页码模式（M9 PageQuery），缺席走 beforeId 游标 + limit 缺省 20 ≤50。
     *
     * @return 游标模式 200 + {total, items[], nextBeforeId}；页码模式 200 + {total, items[], page, size}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "read", required = false) String read,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        PageQuery pageQuery = PageQuery.resolve(page, size, beforeId);
        if (pageQuery != null) {
            rejectLimitInPageMode(limit);
            return Result.ok(
                    queryService.listPaged(
                            currentUserId(),
                            level,
                            eventType,
                            direction,
                            read,
                            pageQuery.page(),
                            pageQuery.size()));
        }
        return Result.ok(
                queryService.list(
                        currentUserId(), level, eventType, direction, read, beforeId, limit));
    }

    /** limit 为游标模式专属（页码模式请用 size——M9/资讯库同例）。 */
    private static void rejectLimitInPageMode(Integer limit) {
        if (limit != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "limit 仅游标模式可用（页码模式请使用 size）");
        }
    }

    /** 卡片详情（逻辑链/logic_inputs 快照/标的区可跳转）。 */
    @GetMapping("/{id}")
    public Result<RecommendationCardDetailView> detail(@PathVariable("id") long id) {
        return Result.ok(queryService.detail(currentUserId(), id));
    }

    /** 四动作反馈（body {action, subjectCode?}——ADD_WATCHLIST 必带 subjectCode；幂等 1h 窗口直返）。 */
    @PostMapping("/{id}/feedback")
    public Result<RecommendationFeedbackService.FeedbackResult> feedback(
            @PathVariable("id") long id, @RequestBody FeedbackRequest body) {
        return Result.ok(
                feedbackService.feedback(currentUserId(), id, body.action(), body.subjectCode()));
    }

    /** 已读 + 隐式采纳（前端卡片任一跳转前 fire-and-forget；幂等 200 直返）。 */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable("id") long id) {
        feedbackService.markRead(currentUserId(), id);
        return Result.ok();
    }

    /** 采纳统计（adopt-v1：曝光①推送送达 + ②视口曝光 / 采纳 / 采纳率 / basis）。 */
    @GetMapping("/stats")
    public Result<RecommendationStatsView> stats(
            @RequestParam(value = "date", required = false) String date) {
        return Result.ok(queryService.stats(currentUserId(), date));
    }

    /** 反馈请求体（action 四枚举；subjectCode 仅 ADD_WATCHLIST 消费）。 */
    public record FeedbackRequest(String action, String subjectCode) {}

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
