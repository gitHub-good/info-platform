package com.info.platform.interfaces.push;

import com.info.platform.application.push.NotificationHistory;
import com.info.platform.application.push.PushService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.push.PushType;
import com.info.platform.interfaces.common.Result;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 推送接口（对齐技术方案 §4.1.3）。
 *
 * <p>所有端点受 JWT 保护（T17 {@code JwtAuthFilter} 写入 {@code UserContext}）——SSE 长连接在握手期校验 access 令牌，
 * 鉴权失败由过滤器返 401+1003；history 接口同受保护。userId 取 {@link UserContext}（接口层只校验+编排，业务在 {@link
 * PushService}）。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/notifications/stream} —— SSE 长连接（{@code text/event-stream}），注册 {@link
 *       SseEmitter} 并补拉待推记录； 客户端重连带 {@code Last-Event-ID} 头按 id 补拉。
 *   <li>{@code GET /api/v1/notifications?cursor=&type=} —— history 游标分页（{@code id > cursor LIMIT
 *       20}，§4.4 防深分页）。
 * </ul>
 *
 * <p>SSE 事件格式：{@code event:<type>\nid:<recordId>\ndata:<json>\n\n}（由 {@code SseConnectionRegistry}
 * 装配）。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final PushService pushService;

    public NotificationController(PushService pushService) {
        this.pushService = pushService;
    }

    /**
     * SSE 长连接：注册当前用户的 {@link SseEmitter}，并按 {@code Last-Event-ID} 补拉待推记录。
     *
     * @param lastEventId 客户端重连携带的最近事件 id（push_record.id），null 表示首次连接
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        long userId = currentUserId();
        log.info("SSE 流连接 userId={} lastEventId={}", userId, lastEventId);
        return pushService.openStream(userId, lastEventId);
    }

    /**
     * 推送历史游标分页。
     *
     * @param cursor 上一页最后一条 id（null 表示首页）
     * @param type 推送类型过滤（SSE 事件名，如 {@code anomaly}；空表示不限类型，未知值返 400）
     */
    @GetMapping
    public Result<NotificationHistory> history(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) String type) {
        long userId = currentUserId();
        PushType typeFilter = parseType(type);
        return Result.ok(pushService.history(userId, cursor, typeFilter));
    }

    /** 解析 type 参数：空白 → null（不限）；未知值 → 400（PARAM_INVALID）。 */
    private static PushType parseType(String type) {
        Optional<PushType> parsed = PushType.fromName(type);
        if (parsed.isPresent() || type == null || type.isBlank()) {
            return parsed.orElse(null);
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "未知推送类型: " + type);
    }

    private static long currentUserId() {
        UserContext.Principal principal = UserContext.get();
        if (principal == null) {
            // 受保护端点经 JwtAuthFilter 已写入 UserContext；防御性 fail-fast
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "未认证上下文");
        }
        return principal.userId();
    }
}
