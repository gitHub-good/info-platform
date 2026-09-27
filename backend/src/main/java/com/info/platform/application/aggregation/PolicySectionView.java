package com.info.platform.application.aggregation;

import java.util.List;

/**
 * 详情页政策分区对象契约（V2.3-M23 T202，方案 §4.3——{@code data.policies} 从 List 换为分区对象， T202/T206 同批切换）。
 *
 * <pre>
 * "policies": {
 *   "items": [ { id, title, url, publishedAt, sourceName, matchType: "SUBJECT | INDUSTRY" } ],
 *   "fallback": { "items": [ ...同构无 matchType... ], "note": "暂无与该标的行业直接相关的政策，以下为近期宏观政策" },
 *   "basis": "policy-scope-v1"
 * }
 * </pre>
 *
 * <p>关联命中段与宏观兜底段二选其一（items 非空则 fallback 为 null）——MISSING 语义构造性消除； sourceStatus.policy 恒
 * "ok"（库内查询无外呼三态，见 {@code AggregationService}）。
 */
public record PolicySectionView(List<Item> items, Fallback fallback, String basis) {

    /** 关联段/兜底段同容量（沿 M12 分区容量惯例，常量集中定义于 {@code SubjectPolicySectionService}）。 */
    public record Item(
            Long id,
            String title,
            String url,
            String publishedAt,
            String sourceName,
            String matchType) {}

    /** 宏观兜底段（REQ 拍板三：口径明示文案——不冒充关联）。 */
    public record Fallback(List<Item> items, String note) {}
}
