package com.info.platform.domain.recommendation;

/**
 * 降噪组合状态（{@code recommendation_mute.status}，M16 方案 §4.1/§4.7）：ACTIVE ↔ LIFTED 可逆（沿 Subscription
 * reactivate 模式——再次 DISLIKE 翻回 ACTIVE 续期）。
 */
public enum MuteStatus {
    /** 降频中（now &lt; muted_until 即拦截推送）。 */
    ACTIVE,
    /** 已撤销（后续新卡恢复推送资格；历史 SKIPPED_MUTED 卡不补推）。 */
    LIFTED;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static MuteStatus fromName(String name) {
        if (name != null) {
            for (MuteStatus status : values()) {
                if (status.name().equals(name)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知 mute status: " + name);
    }
}
