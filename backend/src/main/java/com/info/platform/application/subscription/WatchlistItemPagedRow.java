package com.info.platform.application.subscription;

import java.math.BigDecimal;

/**
 * 清单项分页行（{@code GET /watchlists/{id}/items} 响应元素）：清单项字段 + 标的摘要 + 行情两列内联。
 *
 * <p>行情经聚合域 {@code AggregationService.getQuotes} 实时取数（享 QUOTE 源缓存）；取数失败/类型不适用 → {@code
 * price}/{@code changePct} 为 null（排序恒沉底，前端显示「—」，与既有 quotes 端点降级语义一致）。 标的摘要随行情同源返回，前端不再二次调 {@code
 * /subjects/quotes}。
 *
 * @param industry 标的行业（申万一级；可空）
 * @param price 最新价（元；行情缺失 null）
 * @param changePct 日涨跌幅（%；行情缺失 null）
 */
public record WatchlistItemPagedRow(
        Long id,
        Long subjectId,
        BigDecimal anomalyThreshold,
        String subjectCode,
        String name,
        String market,
        String industry,
        BigDecimal price,
        BigDecimal changePct) {}
