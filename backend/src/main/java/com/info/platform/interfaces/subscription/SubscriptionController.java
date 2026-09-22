package com.info.platform.interfaces.subscription;

import com.info.platform.application.subscription.SubscriptionListView;
import com.info.platform.application.subscription.SubscriptionService;
import com.info.platform.application.subscription.SubscriptionView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionType;
import com.info.platform.interfaces.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个性化订阅管理接口（对齐技术方案 §4.1.6）。
 *
 * <p>所有端点受 JWT 保护（T17 JwtAuthFilter 写入 {@code UserContext}），行级权限在应用层 {@link SubscriptionService} 取
 * {@code UserContext.get().userId()} 落实——用户只能操作自己的订阅。
 *
 * <p>幂等（§4.4「幂等业务语义键」= userId + subType + subKey，Idempotency-Key 头为业务语义键）： subscription_config 表无
 * idempotency_key 列，去重走自然键——已订阅直返 / 已退订重新激活（复用同一行，不新增）； 重复请求不产生重复行、不报错。 Idempotency-Key
 * 头接受并记审计日志，实际去重由自然键承担。 DB {@code UNIQUE(user_id, sub_type, sub_key)} 为最后防线。
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * 列出当前用户订阅（游标分页，可选按类型过滤）。
     *
     * @param type 订阅类型（1~4），可选；非法值→2001/400
     * @param cursor 上一页末条 id，可选；缺省首页
     */
    @GetMapping
    public Result<SubscriptionListView> list(
            @RequestParam(name = "type", required = false) Integer type,
            @RequestParam(name = "cursor", required = false) Long cursor) {
        SubscriptionType subType = type == null ? null : toSubType(type);
        return Result.ok(subscriptionService.listSubscriptions(subType, cursor));
    }

    /** 订阅（幂等：已订阅直返 / 已退订重新激活 / 全新 INSERT）。 */
    @PostMapping
    public Result<SubscriptionView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        log.debug(
                "订阅 Idempotency-Key={}, subType={}, subKey={}",
                idempotencyKey,
                request.subType(),
                request.subKey());
        SubscriptionType subType = toSubType(request.subType());
        SubscriptionChannel channel = toChannel(request.channel());
        return Result.ok(subscriptionService.subscribe(subType, request.subKey(), channel));
    }

    /** 退订（行级校验；不存在→30050/404，越权→30051/403，已退订幂等无副作用）。 */
    @DeleteMapping("/{id}")
    public Result<Void> unsubscribe(@PathVariable Long id) {
        subscriptionService.unsubscribe(id);
        return Result.ok();
    }

    /** 订阅请求体：subType 1~4 必填、subKey 非空、channel 1~2 可空（缺省应用内）。 */
    public record CreateSubscriptionRequest(
            @NotNull(message = "subType 不能为空") @Min(value = 1, message = "subType 取值 1~4")
                    @Max(value = 4, message = "subType 取值 1~4")
                    Integer subType,
            @NotBlank(message = "subKey 不能为空") String subKey,
            @Min(value = 1, message = "channel 取值 1~2") @Max(value = 2, message = "channel 取值 1~2")
                    Integer channel) {}

    private static SubscriptionType toSubType(Integer code) {
        if (code == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "subType 不能为空");
        }
        try {
            return SubscriptionType.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "subType 取值 1~4");
        }
    }

    private static SubscriptionChannel toChannel(Integer code) {
        if (code == null) {
            return null; // 缺省走应用内渠道（service/entity 层兜底）
        }
        try {
            return SubscriptionChannel.fromCode(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "channel 取值 1~2");
        }
    }
}
