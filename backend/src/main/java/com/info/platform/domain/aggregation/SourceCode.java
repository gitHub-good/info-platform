package com.info.platform.domain.aggregation;

/**
 * 数据源类型（六类），作为 {@link SourceAdapter} 的自我标识与缓存/限频分区键。
 *
 * <p>领域层纯净枚举，不依赖任何框架类型。缓存 TTL 等策略属基础设施层（{@code
 * infrastructure.common.SourceCache}），不在此耦合，保持领域层可脱离容器单测、可移植。
 */
public enum SourceCode {
    /** 行情源 */
    QUOTE,
    /** 财务源 */
    FINANCE,
    /** 估值源 */
    VALUATION,
    /** 公告源 */
    ANNOUNCE,
    /** 新闻源 */
    NEWS,
    /** 政策事件源 */
    POLICY
}
