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

    /**
     * 由 LLM 输出的倾向标签（Spike-2 §7.3 政策解读模板 {@code bias} 字段，取值 {@code 利好|利空|中性}）映射为枚举。
     *
     * <p>T28 {@code PolicyTendencyService} 调 LLM 得 {@link
     * com.info.platform.domain.ai.BriefContent#bias()} 后用本方法转 {@code AiTendency} 写入 {@code
     * policy_item.ai_tendency}。用 {@code contains} 容错模型偶发附加标点/修饰（如「利好。」「整体利好」）； null/空白/无法识别回 {@link
     * #UNJUDGED}（保持 0，不阻断）。
     *
     * @param bias LLM 输出倾向标签原文，可空
     */
    public static AiTendency fromBias(String bias) {
        if (bias == null) {
            return UNJUDGED;
        }
        String s = bias.strip();
        if (s.isEmpty()) {
            return UNJUDGED;
        }
        if (s.contains("利好")) {
            return BULLISH;
        }
        if (s.contains("利空")) {
            return BEARISH;
        }
        if (s.contains("中性")) {
            return NEUTRAL;
        }
        return UNJUDGED;
    }
}
