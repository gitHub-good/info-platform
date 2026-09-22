package com.info.platform.domain.policy;

/**
 * 政策倾向判断枚举（领域层纯净，对齐技术方案 §4.2 policy_item.ai_tendency）。
 *
 * <p>T24 全量置 {@link #UNJUDGED}（0 未判）；T28 AI 倾向判断落地后按利好/利空/中性填值。
 */
public enum AiTendency {
    /** 未判（T24 默认，T28 前） */
    UNJUDGED(0),
    /** 利好 */
    BULLISH(1),
    /** 利空 */
    BEARISH(2),
    /** 中性 */
    NEUTRAL(3);

    private final int code;

    AiTendency(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按存储 code 反查；null/未知 code 容错回 {@link #UNJUDGED}（防御脏数据）。 */
    public static AiTendency fromCode(Integer code) {
        if (code == null) {
            return UNJUDGED;
        }
        for (AiTendency t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNJUDGED;
    }
}
