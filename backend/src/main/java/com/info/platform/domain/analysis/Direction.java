package com.info.platform.domain.analysis;

/**
 * 事件方向（{@code event_item.direction}，M15 T122，方案 §4.4 模板契约）：BULLISH 利好 / BEARISH 利空 / NEUTRAL 中性。
 *
 * <p>v1 只做事件属性落库与展示；情感倾向参与热度加权留 M17（REQ 条目 12 Could）。
 */
public enum Direction {
    /** 利好。 */
    BULLISH("利好"),
    /** 利空。 */
    BEARISH("利空"),
    /** 中性。 */
    NEUTRAL("中性");

    private final String displayName;

    Direction(String displayName) {
        this.displayName = displayName;
    }

    /** 中文展示名（事件流卡片徽章用）。 */
    public String displayName() {
        return displayName;
    }

    /** 从持久化/模型文本反查（未知值返回 null——非法枚举行进重试批）。 */
    public static Direction fromName(String name) {
        if (name == null) {
            return null;
        }
        for (Direction direction : values()) {
            if (direction.name().equals(name)) {
                return direction;
            }
        }
        return null;
    }
}
