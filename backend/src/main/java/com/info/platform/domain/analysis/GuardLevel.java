package com.info.platform.domain.analysis;

/**
 * 管道成本护栏级别（M15 T125，方案 §3.5/§4.6 裁决 5）：{@code llm_call_log} scene 5/6/7 当日 SUCCESS
 * 成本对日预算的两级派生，无表无状态—— 每次批执行前现算（换日查询窗口自动滚动即恢复）。
 *
 * <ul>
 *   <li>{@link #NORMAL}：成本 &lt; 60% 日预算——全级运行；
 *   <li>{@link #DEGRADED}：成本 ≥ 60%——跳过 L2（含还 DEFERRED 旧账），L1 与日报保留（REQ 拍板四-1）；
 *   <li>{@link #FUSED}：成本 ≥ 90%——L1/L2/日报全跳过（L0/快照零成本照常）+ PIPELINE_FUSED 告警一次。
 * </ul>
 */
public enum GuardLevel {
    NORMAL("正常"),
    DEGRADED("降级（跳过 L2）"),
    FUSED("熔断（管道 AI 全停）");

    private final String displayName;

    GuardLevel(String displayName) {
        this.displayName = displayName;
    }

    /** 展示名（降级横幅与状态页文案）。 */
    public String displayName() {
        return displayName;
    }
}
