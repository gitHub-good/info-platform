package com.info.platform.domain.aggregation;

/**
 * 数据源类型（六类），作为 {@link SourceAdapter} 的自我标识与缓存/限频分区键。
 *
 * <p>V2.3-M23 T203：{@code POLICY} 枚举删除（轨 A 退役，ADR-0062 裁决二）——政策条目改走资讯源体系 （gov_policy
 * 目录源），详情政策分区为库内查询分区（AggregationService 五源化）；枚举穷尽 switch 即 编译期兜底，任何残留引用面在编译期暴露。
 *
 * <p>领域层纯净枚举，不依赖任何框架类型。缓存 TTL 等策略属基础设施层（{@code
 * infrastructure.common.SourceCache}），不在此耦合，保持领域层可脱离容器单测、可移植。
 *
 * <p>{@link #EVENT} 为 T08 实现偏差新增（ADR-0013）：原方案复用环境既有 event-monitor 技能， 经核实不存在，改为读本地 {@code
 * anomaly_event} 表（T13 异动检测引擎落库）。
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
    /** 事件监控源（本地 anomaly_event 表，ADR-0013） */
    EVENT
}
