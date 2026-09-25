package com.info.platform.domain.analysis;

/**
 * L2 事件提取状态（{@code news_analysis.l2_status} 持久化为枚举名文本，M15 T122 填逻辑，本批仅落枚举与建表）。
 *
 * <p>六态对齐方案 §4.1/§4.4：SKIP（未命中重要性预筛）/ SELECTED（进批）/ EXTRACTED / NO_EVENT / DEFERRED（命中但配额满，
 * 如实统计不静默丢弃）/ FAILED。
 */
public enum L2Status {
    /** 未命中重要性预筛（缺省态）。 */
    SKIP,
    /** 命中预筛进批（待提取）。 */
    SELECTED,
    /** 已提取结构化事件（event_item 已落行）。 */
    EXTRACTED,
    /** 模型判定无可提取事件。 */
    NO_EVENT,
    /** 命中预筛但当日配额满（次日低峰先还旧账，如实统计）。 */
    DEFERRED,
    /** 提取失败（当日重试 ≤maxRetries）。 */
    FAILED;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static L2Status fromName(String name) {
        if (name != null) {
            for (L2Status status : values()) {
                if (status.name().equals(name)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知 l2_status: " + name);
    }
}
