package com.info.platform.application.policy;

/**
 * 政策条目关联 L2 政策发布事件视图（V2.3-M23 T201，REQ 拍板四：ai_tendency 退役由 L2 事件 direction 承接）。
 *
 * <p>§4.2 契约：event_item WHERE news_id=:id（UNIQUE 至多一条）；direction 即倾向承接面（BULLISH 利好 / BEARISH 利空 /
 * NEUTRAL 中性，trace-v1 可下钻事件流）。无关联事件时空数组。
 */
public record RelatedEventView(
        Long id,
        String eventType,
        String summary,
        String direction,
        String importance,
        String eventDate) {}
