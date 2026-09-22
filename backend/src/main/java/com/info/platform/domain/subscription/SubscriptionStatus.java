package com.info.platform.domain.subscription;

/**
 * 订阅状态（对齐技术方案 §4.2 subscription_config.status TINYINT，1 订阅中 / 0 已退订）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。退订走软退订（status 1→0，行保留），便于：
 *
 * <ul>
 *   <li>重新订阅复用同一 {@code (userId, subType, subKey)} 自然键行（reactivate 翻回 1，不新增行）。
 *   <li>退订后续不推送不入流——推送目标解析（{@code SubscriptionConfigSubscriptionResolver}）与信息流命中（T27） 均按 {@code
 *       status=1} 过滤，已退订的订阅不参与匹配（对齐 PRD 故事 5 场景 3「退订降噪」）。
 * </ul>
 */
public enum SubscriptionStatus {
    UNSUBSCRIBED(0),
    SUBSCRIBED(1);

    private final int code;

    SubscriptionStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SubscriptionStatus fromCode(int code) {
        for (SubscriptionStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 subscriptionStatus: " + code);
    }
}
