package com.info.platform.application.subscription;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionType;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅应用服务：subscribe 幂等 / unsubscribe 软退订 / list 游标分页 + 行级权限（对齐技术方案 §4.1.6、§4.4）。
 *
 * <p>行级权限：每个方法首步 {@code long userId = UserContext.get().userId()}（T17 JwtAuthFilter 写入）， 所有
 * Repository 调用带 {@code userId}——端口层 {@code WHERE user_id=?} 即约束，用户只能操作自己的订阅。 越权访问订阅：订阅存在但非本人 →
 * {@link ErrorCode#SUBSCRIPTION_FORBIDDEN}（30051/403）；订阅不存在 → {@link
 * ErrorCode#SUBSCRIPTION_NOT_FOUND}（30050/404）。
 *
 * <p>幂等（§4.4「幂等业务语义键」= userId + subType + subKey，subscription_config 表无 idempotency_key 列，走自然键去重）：
 *
 * <ul>
 *   <li>已订阅（status=1）→ 直返，无副作用（重复请求不产生重复行，不报错）。
 *   <li>已退订（status=0）→ 重新激活（reactivate 翻 status=1，复用同一 {@code (userId, subType, subKey)} 行，UPDATE
 *       不新增行）。
 *   <li>全新 → INSERT。DB {@code UNIQUE(user_id, sub_type, sub_key)} 为最后防线。
 * </ul>
 *
 * <p>退订走软退订（status 1→0，行保留），便于重新订阅复用同键 + 退订后不推送不入流（解析方与信息流均按 status=1 过滤）。 重复退订（已
 * status=0）幂等无副作用不报错。
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** list 游标分页单页条数（§4.4 游标分页，LIMIT 20，与 push history 一致）。 */
    static final int LIST_PAGE_SIZE = 20;

    private final SubscriptionRepository repository;

    public SubscriptionService(SubscriptionRepository repository) {
        this.repository = repository;
    }

    /**
     * 订阅（幂等）：已订阅直返 / 已退订重新激活 / 全新 INSERT。
     *
     * @param subType 订阅类型
     * @param subKey 订阅键（标的订阅时为 String.valueOf(subjectId)）
     * @param channel 推送渠道，null 走默认应用内渠道
     */
    @Transactional
    public SubscriptionView subscribe(
            SubscriptionType subType, String subKey, SubscriptionChannel channel) {
        long userId = currentUserId();
        Optional<Subscription> existing =
                repository.findByOwnerIdAndTypeAndKey(userId, subType.code(), subKey);
        if (existing.isPresent()) {
            Subscription sub = existing.get();
            if (sub.isActive()) {
                // 幂等：已订阅直返，无副作用（重复请求不产生重复行）
                log.info(
                        "订阅幂等直返: id={}, userId={}, subType={}, subKey={}",
                        sub.getId(),
                        userId,
                        subType,
                        subKey);
                return SubscriptionView.from(sub);
            }
            // 重新激活：已退订→翻 status=1，复用同一自然键行
            sub.reactivate();
            Subscription saved = repository.save(sub);
            log.info(
                    "重新激活订阅: id={}, userId={}, subType={}, subKey={}",
                    saved.getId(),
                    userId,
                    subType,
                    subKey);
            return SubscriptionView.from(saved);
        }
        Subscription saved = repository.save(Subscription.create(userId, subType, subKey, channel));
        log.info(
                "新增订阅: id={}, userId={}, subType={}, subKey={}",
                saved.getId(),
                userId,
                subType,
                subKey);
        return SubscriptionView.from(saved);
    }

    /** 退订（行级校验 + 软退订；不存在→30050/404，越权→30051/403，已退订幂等无副作用）。 */
    @Transactional
    public void unsubscribe(Long id) {
        long userId = currentUserId();
        if (!repository.existsById(id)) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        }
        Subscription sub =
                repository
                        .findByOwnerIdAndId(userId, id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_FORBIDDEN));
        if (!sub.isActive()) {
            // 幂等：已退订无副作用
            log.info("退订幂等直返（已退订）: id={}, userId={}", id, userId);
            return;
        }
        sub.unsubscribe();
        repository.save(sub); // @Version 乐观锁生效
        log.info("退订成功: id={}, userId={}", id, userId);
    }

    /**
     * 列出当前用户订阅（游标分页，可选按类型过滤）。
     *
     * @param type 订阅类型，null 表示全部类型
     * @param cursor 上一页末条 id，null 表示首页
     */
    public SubscriptionListView listSubscriptions(SubscriptionType type, Long cursor) {
        long userId = currentUserId();
        Integer subTypeCode = type != null ? type.code() : null;
        List<Subscription> subs =
                repository.findByOwnerIdCursor(userId, subTypeCode, cursor, LIST_PAGE_SIZE);
        List<SubscriptionView> items = subs.stream().map(SubscriptionView::from).toList();
        Long nextCursor = items.size() == LIST_PAGE_SIZE ? items.get(items.size() - 1).id() : null;
        return new SubscriptionListView(items, nextCursor);
    }

    private static long currentUserId() {
        UserContext.Principal principal = UserContext.get();
        if (principal == null) {
            // 不应发生：受保护接口经 JwtAuthFilter 已写入 UserContext；防御性 fail-fast
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "未认证上下文");
        }
        return principal.userId();
    }
}
