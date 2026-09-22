package com.info.platform.domain.subscription;

/**
 * 订阅类型（对齐技术方案 §4.1.6 subType + §4.2 subscription_config.sub_type TINYINT）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。
 *
 * <ul>
 *   <li>{@link #TOPIC} 主题订阅：subKey=主题词（如「半导体国产替代」），T27 信息流按关键词命中。
 *   <li>{@link #SUBJECT} 标的订阅：subKey=String.valueOf(subjectId)，T26 后 {@code
 *       SubscriptionConfigSubscriptionResolver} 按 sub_type=2 + sub_key=subjectId 解析异动推送目标。
 *   <li>{@link #EVENT_TYPE} 事件类型订阅：subKey=事件类型枚举值（重大公告/业绩预增等），事件入库按类型匹配。
 *   <li>{@link #POLICY_THEME} 政策主题订阅：subKey=政策主题词（如「货币政策」），政策流按主题命中。
 * </ul>
 */
public enum SubscriptionType {
    TOPIC(1),
    SUBJECT(2),
    EVENT_TYPE(3),
    POLICY_THEME(4);

    private final int code;

    SubscriptionType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SubscriptionType fromCode(int code) {
        for (SubscriptionType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 subscriptionType: " + code);
    }
}
