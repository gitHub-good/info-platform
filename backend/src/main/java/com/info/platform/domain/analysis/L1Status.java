package com.info.platform.domain.analysis;

/**
 * L1 批量归类状态（{@code news_analysis.l1_status} 持久化为枚举名文本，M15 T121，方案 §4.1/§4.3）。
 *
 * <p>三态 + 条件 UPDATE 状态机（方案库 10 裁剪采用）：{@code WHERE l1_status IN ('PENDING','FAILED')} 守卫落库，
 * 重跑无副作用（幂等，ADR-0046 裁决「批处理幂等」）。FAILED 当日重试 ≤maxRetries，次日 24h 待处理窗口再试一轮。
 */
public enum L1Status {
    /** 待归类（含当日重试语义——FAILED 行经 attempts 守卫后重新进批）。 */
    PENDING,
    /** 归类完成（主分类/置信度已落库）。 */
    DONE,
    /** 归类失败（拆批递归至单条仍失败；当日重试 ≤maxRetries，次日窗口补跑）。 */
    FAILED;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}）。 */
    public static L1Status fromName(String name) {
        if (name != null) {
            for (L1Status status : values()) {
                if (status.name().equals(name)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知 l1_status: " + name);
    }
}
