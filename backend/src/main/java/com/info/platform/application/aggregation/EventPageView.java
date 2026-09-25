package com.info.platform.application.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 事件分区子端点响应视图（M12 T91，方案 §4.1.2 / ADR-0037 决策 1）。
 *
 * <p>条目字段与聚合路径 {@code SubjectDetail.events[]}
 * 逐字段一致（anomalyType/changePct/currentPrice/triggerTime/detail——同一 adapter 同一 toItem 产出）；total 为 7
 * 天窗内精确 count（与页切片同窗口同口径，窗界=请求时刻-7d）。 越界页 200 + 空列表 + total 如实；空窗 {@code total=0} + {@code
 * sourceStatus:missing}（维持聚合页现状 missing 兜底语义，前端不渲染分页条）。 归 application 层同 {@link
 * AnnouncementPageView} 先例（方案附录 A 原列 interfaces 层，层间无环约束落位 application，JSON 契约不变）。
 *
 * @param items 当前页条目（可为空——越界页）
 * @param page 当前页码（回显请求值）
 * @param size 页大小（回显：显式请求值或缺省 10——原 MAX_ITEMS 语义升级为页大小）
 * @param total 7 天窗内精确总数（本地 count，毫秒级；空窗为 0）
 * @param sourceStatus ok | missing | failed | timeout（与聚合口径同源同码）
 * @param source 生效 provider 标注（本地「事件监控」）
 */
public record EventPageView(
        List<Map<String, Object>> items,
        int page,
        int size,
        long total,
        String sourceStatus,
        String source) {}
