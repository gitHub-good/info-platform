package com.info.platform.domain.feed;

import java.time.Instant;

/**
 * 资讯统一库条目实体（{@code news_item} 表，M13 T104，ADR-0039）。
 *
 * <p>标题+摘要级快讯/资讯条目（正文不入库，M13 非目标）；跨源指纹全局唯一 + 源内 external_id 唯一双保险去重； 历史条目随源软删保留（status=2
 * 源归档由后续批次使用，本批不标记）。
 */
public record FeedItem(
        Long id,
        long sourceId,
        String externalId,
        String title,
        String summary,
        String url,
        String author,
        Instant publishedAt,
        Instant fetchedAt,
        String fingerprint,
        int status,
        Instant createdAt,
        Instant updatedAt) {

    /** 条目状态：1=正常。 */
    public static final int STATUS_NORMAL = 1;

    /** 落库前构建（id/时间戳由仓储回填；status 缺省正常）。 */
    public static FeedItem newOf(
            long sourceId,
            String externalId,
            String title,
            String summary,
            String url,
            String author,
            Instant publishedAt,
            Instant fetchedAt,
            String fingerprint) {
        return new FeedItem(
                null,
                sourceId,
                externalId,
                title,
                summary,
                url,
                author,
                publishedAt,
                fetchedAt,
                fingerprint,
                STATUS_NORMAL,
                null,
                null);
    }
}
