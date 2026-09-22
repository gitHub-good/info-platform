package com.info.platform.domain.subscription;

import java.time.Instant;
import java.util.Objects;

/**
 * 订阅实体（subscription_config）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/version/时间戳）由基础设施层 {@code SubscriptionRepositoryImpl} 经
 * {@link #reconstruct} 回填。
 *
 * <p>行级权限：{@code userId} 标识订阅归属，仓储所有面向用户的查询带 {@code ownerUserId} 过滤； 服务层取 {@code
 * UserContext.get().userId()} 作 {@code ownerUserId}，用户只能操作自己的订阅。
 *
 * <p>幂等与软退订（§4.4「幂等业务语义键」= userId + subType + subKey）：退订走 {@link #unsubscribe} 翻 status 1→0（行保留），
 * 重新订阅走 {@link #reactivate} 翻回 1（复用同一自然键行，不新增行）；DB {@code UNIQUE(user_id, sub_type, sub_key)}
 * 为最后防线。退订后不推送不入流——解析方与信息流均按 {@code status=1} 过滤。
 */
public class Subscription {

    private Long id;
    private Long userId;
    private SubscriptionType subType;
    private String subKey;
    private SubscriptionChannel channel;
    private SubscriptionStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private Subscription() {}

    /** 构建新订阅（id/version/时间戳留空，落库后回填）；channel 为 null 走默认应用内渠道。 */
    public static Subscription create(
            Long userId, SubscriptionType subType, String subKey, SubscriptionChannel channel) {
        Objects.requireNonNull(userId, "userId 必填");
        Objects.requireNonNull(subType, "subType 必填");
        if (subKey == null || subKey.isBlank()) {
            throw new IllegalArgumentException("subKey 不能为空");
        }
        Subscription s = new Subscription();
        s.userId = userId;
        s.subType = subType;
        s.subKey = subKey;
        s.channel = channel != null ? channel : SubscriptionChannel.IN_APP;
        s.status = SubscriptionStatus.SUBSCRIBED;
        return s;
    }

    /** 从持久化数据重建实体（基础设施层落库后回读时用）。 */
    public static Subscription reconstruct(
            Long id,
            Long userId,
            SubscriptionType subType,
            String subKey,
            SubscriptionChannel channel,
            SubscriptionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        Subscription s = new Subscription();
        s.id = id;
        s.userId = userId;
        s.subType = subType;
        s.subKey = subKey;
        s.channel = channel;
        s.status = status;
        s.version = version;
        s.createdAt = createdAt;
        s.updatedAt = updatedAt;
        return s;
    }

    /** 重新激活（已退订→重新订阅）：翻 status 回订阅中。复用同一自然键行，不新增行（幂等）。 */
    public void reactivate() {
        this.status = SubscriptionStatus.SUBSCRIBED;
    }

    /** 退订（软退订）：翻 status 为已退订，行保留（便于重新订阅 + 退订后不推送不入流）。 */
    public void unsubscribe() {
        this.status = SubscriptionStatus.UNSUBSCRIBED;
    }

    public boolean isActive() {
        return status == SubscriptionStatus.SUBSCRIBED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public SubscriptionType getSubType() {
        return subType;
    }

    public String getSubKey() {
        return subKey;
    }

    public SubscriptionChannel getChannel() {
        return channel;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
