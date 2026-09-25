package com.info.platform.application.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 新闻分区子端点响应视图（M12 T92，方案 §4.1.3 / ADR-0037 决策 1/D4）。
 *
 * <p>条目字段与聚合路径 {@code SubjectDetail.news[]} 逐字段一致（externalId/title/publishedAt/summary/url/source/keywords——同一
 * adapter 同一 itemMapping 产出，externalId 为前端去重稳定标识）。后端契约<b>无状态</b>：单请求 = 单源页过滤命中 +
 * {@code hasMore} 源页耗尽信号——「新增」判定归前端（累积 externalId 集合是唯一真相）。 无命中但源页有条目 → items
 * 空但 hasMore 按源页满否如实；空源页/流耗尽 → items 空 + hasMore:false。 归 application 层同 {@link
 * AnnouncementPageView} 先例（层间无环约束落位，JSON 契约不变）。
 *
 * @param items 该源页过滤后的命中条目（可为空）
 * @param page 源页码（回显请求值）
 * @param size 源页大小（回显运维配置 newsPageSize——不属调用方自由度，端点不接受 size 参数）
 * @param hasMore 源页未耗尽（本源页条数 == 源页大小且非空；空页/不满页 false）
 * @param sourceStatus ok | missing | failed | timeout（与聚合口径同源同码）
 * @param source 生效 provider 标注（「新浪财经新闻」）
 */
public record NewsPageView(
        List<Map<String, Object>> items,
        int page,
        int size,
        boolean hasMore,
        String sourceStatus,
        String source) {}
