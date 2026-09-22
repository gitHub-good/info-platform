package com.info.platform.application.subscription;

import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionStatus;

/**
 * 订阅视图（应用层返回值，供接口层包装为 {@code Result}）。
 *
 * <p>对齐技术方案 §4.1.6：不含内部 userId（前端即当前用户）/version/时间戳。 subType/channel/status 以 code 暴露（前端按 code 渲染）。
 */
public record SubscriptionView(Long id, int subType, String subKey, int channel, int status) {

    /** 领域实体 → 视图（枚举映射为 code）。 */
    static SubscriptionView from(Subscription subscription) {
        SubscriptionChannel channel =
                subscription.getChannel() == null
                        ? SubscriptionChannel.IN_APP
                        : subscription.getChannel();
        SubscriptionStatus status =
                subscription.getStatus() == null
                        ? SubscriptionStatus.SUBSCRIBED
                        : subscription.getStatus();
        return new SubscriptionView(
                subscription.getId(),
                subscription.getSubType().code(),
                subscription.getSubKey(),
                channel.code(),
                status.code());
    }
}
