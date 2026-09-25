package com.info.platform.application.feed;

/** 资讯流条目视图（GET /api/v1/news-items 每条，T104）。publishedAt/fetchedAt 为 ISO-8601 整秒文本。 */
public record NewsItemView(
        Long id,
        Long sourceId,
        String sourceCode,
        String sourceName,
        String title,
        String summary,
        String url,
        String author,
        String publishedAt,
        String fetchedAt) {}
