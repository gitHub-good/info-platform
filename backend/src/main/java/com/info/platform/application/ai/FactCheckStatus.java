package com.info.platform.application.ai;

/**
 * 单条事实幻觉校验状态（T21，对齐 Spike-2 §6）。
 *
 * <ul>
 *   <li>{@link #VERIFIED} 已核实——模型数值与数据源真值相对误差 ≤ 容忍阈值（默认 5%）。
 *   <li>{@link #NEED_VERIFY} 待核实——数值不符（超阈值）或源暂不可用（adapter 降级 / 字段缺失），触发整体 status=3。
 *   <li>{@link #SKIPPED} 跳过——无数值（定性事实）或 metric 未命中已知数据源字段键（如 {@code net_profit_yoy}，adapter 不产
 *       YoY，不在校验范围，Spike-2 §6「校验范围仅限...metric 命中 T04 字段键」）。
 * </ul>
 */
public enum FactCheckStatus {
    VERIFIED,
    NEED_VERIFY,
    SKIPPED
}
