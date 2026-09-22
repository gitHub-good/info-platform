package com.info.platform.domain.subscription;

/**
 * 订阅推送渠道（对齐技术方案 §4.2 subscription_config.channel TINYINT，1 应用内 / 2 邮件预留）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。T26 仅落地应用内渠道（SSE 应用内通知中心， 与 T14
 * 推送通道一致），邮件渠道为预留位（后续接入邮件网关时启用，不影响主链路）。
 */
public enum SubscriptionChannel {
    IN_APP(1),
    EMAIL(2);

    private final int code;

    SubscriptionChannel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SubscriptionChannel fromCode(int code) {
        for (SubscriptionChannel c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        throw new IllegalArgumentException("未知 subscriptionChannel: " + code);
    }
}
