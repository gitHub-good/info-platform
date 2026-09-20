package com.info.platform.domain.aggregation;

/**
 * 单源取数结果状态（承载于 {@link SourceResult}）。
 *
 * <ul>
 *   <li>{@link #OK} 取数成功且有数据
 *   <li>{@link #MISSING} 数据源当日无数据 / 被降级跳过（不阻断聚合，前端分区显"暂无数据"）
 *   <li>{@link #FAILED} 取数异常（仍不阻断其他分区，供可观测标注）
 * </ul>
 */
public enum SourceStatus {
    OK,
    MISSING,
    FAILED
}
