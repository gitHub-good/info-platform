package com.info.platform.domain.feed;

import java.time.Instant;

/**
 * 引擎输出条目值对象（通用 RSS/JSON 引擎与预置适配器的统一输出，M13 T101/T102）。
 *
 * <p>领域层纯净 VO：{@code externalId} 源侧条目 id/guid（RSS 无 guid 取 link，可空靠指纹兜底）； {@code cursorValue}
 * 按源声明的游标类型取值（ID → externalId 数值串 / TIME → publishedAt ISO / NONE → null）， 供摄取服务推进增量游标。
 */
public record RawFeedItem(
        String externalId,
        String title,
        String summary,
        String url,
        String author,
        Instant publishedAt,
        String cursorValue) {}
