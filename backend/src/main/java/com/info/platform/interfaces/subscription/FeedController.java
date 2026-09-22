package com.info.platform.interfaces.subscription;

import com.info.platform.application.subscription.FeedListView;
import com.info.platform.application.subscription.FeedService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人信息流接口（对齐技术方案 §4.1.6）。
 *
 * <p>{@code GET /api/v1/feed/personal?cursor=}（Bearer）：订阅命中内容（公告/新闻/政策）+ 每日推荐（Top5）， 按 publishedAt
 * 倒序游标分页（单页 20）。受 JWT 保护（T17 {@code JwtAuthFilter} 写入 {@link
 * UserContext}），服务层取当前用户活跃订阅行级过滤——退订订阅（status=0）不参与匹配，对应内容不入流 （PRD 故事 5 场景 3 退订降噪）。空订阅仅返回每日推荐。
 *
 * <p>响应 {@code FeedItem.type}：{@code announce/news/policy}（订阅命中，附 {@code matchReason}）+ {@code
 * recommendation}（每日推荐，{@code matchReason="每日推荐"}）。{@code FeedItem.keywords[]} 为命中订阅关键词（与
 * matchReason 同源：主题/政策主题为订阅词、标的为标的名、事件类型/推荐为空数组），前端命中词高亮用（T43，UI 方案 §6.2 联判点 5；只加字段，既有字段语义不变）。
 */
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private static final Logger log = LoggerFactory.getLogger(FeedController.class);

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 个人信息流（游标分页）。
     *
     * @param cursor 上一页末条 id；缺省首页
     * @return 200 + {items[], nextCursor}
     */
    @GetMapping("/personal")
    public Result<FeedListView> personal(
            @RequestParam(name = "cursor", required = false) Long cursor) {
        long userId = currentUserId();
        log.debug("个人信息流请求 userId={} cursor={}", userId, cursor);
        return Result.ok(feedService.getPersonalFeed(userId, cursor));
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }
}
