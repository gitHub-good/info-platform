package com.info.platform.domain.valuation;

/**
 * 行业热度投影（{@code industry_heat_snapshot} H24 窗单行 → F2 名次归一输入，M20 方案 §4.2）：31 申万行常驻， 缺行行业 heatNorm 记
 * 0（降级预案——不放大不隐藏）。
 */
public record HeatRow(String industry, double heatScore) {}
