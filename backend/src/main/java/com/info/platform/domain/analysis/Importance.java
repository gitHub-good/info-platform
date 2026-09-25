package com.info.platform.domain.analysis;

/**
 * 事件重要度（{@code event_item.importance}，M15 T122，方案 §4.5 热度系数）：HIGH 1.0 / MEDIUM 0.5 / LOW 0.25。
 *
 * <p>系数（coefficient）同时是 L3 热度公式的 impCoef 输入（裁决 6 冻结缺省）；也是 L2 重要性排序的模型判断输出。
 */
public enum Importance {
    /** 显著影响行业格局或股价（系数 1.0）。 */
    HIGH(1.0),
    /** 明确但局部（系数 0.5）。 */
    MEDIUM(0.5),
    /** 一般动态（系数 0.25）。 */
    LOW(0.25);

    private final double coefficient;

    Importance(double coefficient) {
        this.coefficient = coefficient;
    }

    /** 热度公式 impCoef（无事件条目为 0，由调用方分支——本枚举只承载有事件侧取值）。 */
    public double coefficient() {
        return coefficient;
    }

    /** 从持久化/模型文本反查（未知值返回 null——非法枚举行进重试批）。 */
    public static Importance fromName(String name) {
        if (name == null) {
            return null;
        }
        for (Importance importance : values()) {
            if (importance.name().equals(name)) {
                return importance;
            }
        }
        return null;
    }
}
