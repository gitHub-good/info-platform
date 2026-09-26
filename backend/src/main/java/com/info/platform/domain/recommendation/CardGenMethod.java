package com.info.platform.domain.recommendation;

/** 卡片逻辑链生成方式（{@code recommendation_card.gen_method}，M16 方案 §4.5）：留痕可查不对用户展示（REQ 拍板三）。 */
public enum CardGenMethod {
    /** LLM 语言组织且白名单校验通过。 */
    LLM,
    /** 模板拼接（LLM 失败/被拒/降级态兜底——时效优先不重试）。 */
    TEMPLATE;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static CardGenMethod fromName(String name) {
        if (name != null) {
            for (CardGenMethod method : values()) {
                if (method.name().equals(name)) {
                    return method;
                }
            }
        }
        throw new IllegalArgumentException("未知 card gen_method: " + name);
    }
}
