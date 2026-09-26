package com.info.platform.domain.recommendation;

/**
 * 关联层级（{@code recommendation_card.level}，M16 方案 §4.4 / REQ 拍板一）：三级关联逐级判定，命中多级取最高。
 *
 * <p>系数（coefficient）同时是 recscore-v1 的 levelCoef 输入（REQ 拍板一冻结值 3/2/1——P1 MEDIUM 基础分 6.0 &gt; P2 HIGH
 * 4.0 &gt; P3 HIGH 2.0，画像加成不改变层级×重要性的主序）。
 */
public enum RecLevel {
    /** P1 标的直接：event.subjects ∩（自选 ∪ SUBJECT 订阅）——「直接涉及你关注的标的」。 */
    P1(3.0),
    /** P2 行业：event.affectedIndustries ∩ 行业关注集（双通道，ADR-0051 裁决 4）。 */
    P2(2.0),
    /** P3 订阅：EVENT_TYPE 折算 / 主题词 contains 命中（标的区为空，不硬凑）。 */
    P3(1.0);

    private final double coefficient;

    RecLevel(double coefficient) {
        this.coefficient = coefficient;
    }

    /** recscore-v1 levelCoef（REQ 拍板一冻结值）。 */
    public double coefficient() {
        return coefficient;
    }

    /** 从持久化/入参文本反查（未知值抛 {@code IllegalArgumentException}——白名单校验）。 */
    public static RecLevel fromName(String name) {
        if (name != null) {
            for (RecLevel level : values()) {
                if (level.name().equals(name)) {
                    return level;
                }
            }
        }
        throw new IllegalArgumentException("未知 recommendation level: " + name);
    }
}
