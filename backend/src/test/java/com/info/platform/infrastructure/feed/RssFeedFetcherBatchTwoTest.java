package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 批次二国际 RSS 双源 fixture 单测（M17 T142，REQ-20260926-14 拍板一 #8/#9，MarketWatch T106 先例复制）： Nasdaq·市场
 * 与 WSJ·市场目录配置对真实响应截样本的解析正确性（条数/RSS 2.0 默认映射/pubDate RFC-1123/TIME 游标）。 fixture 均为
 * 2026-09-22 预检三验（robots+可达+结构）锁定后的真实响应截样本（各 3 条）。
 */
class RssFeedFetcherBatchTwoTest {

    private final SourceConfigCodec codec = new SourceConfigCodec();
    private final RssFeedFetcher fetcher = new RssFeedFetcher(RestClient.builder().build());

    private InfoSource catalogSource(String sourceCode) {
        InfoSourceCatalog.PresetEntry entry =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals(sourceCode))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("目录缺失: " + sourceCode));
        return InfoSource.create(
                entry.sourceCode(),
                entry.name(),
                entry.category(),
                entry.adapterType(),
                entry.adapterRef(),
                entry.endpoint(),
                codec.parse(entry.configJson()),
                entry.intervalMinutes(),
                true,
                true);
    }

    @Test
    void parse_nasdaqMarkets_rssDefaultsWithTimeCursor() {
        InfoSource source = catalogSource("nasdaq_markets");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/nasdaq-markets-sample.xml"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实样本 3 条（2026-09-26 02:3x UTC 市场要闻），newest-first
        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.title()).contains("SpaceX");
        assertThat(first.url()).startsWith("https://www.nasdaq.com/articles/");
        // RSS 默认映射：guid 作 externalId、pubDate RFC-1123 解析
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-26T02:35:00Z"));
        // TIME 游标沿 MarketWatch 先例（MW T106）
        assertThat(first.cursorValue()).isEqualTo("2026-09-26T02:35:00Z");
    }

    @Test
    void parse_wsjMarkets_rssDefaultsWithTimeCursor() {
        InfoSource source = catalogSource("wsj_markets");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/wsj-markets-sample.xml"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实样本 3 条（2026-09-25 UTC 市场要闻）
        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.title()).contains("Stock Market News");
        assertThat(first.url()).startsWith("https://www.wsj.com/");
        assertThat(first.publishedAt())
                .isEqualTo(java.time.Instant.parse("2026-09-25T08:52:23Z"));
        assertThat(first.cursorValue()).isEqualTo("2026-09-25T08:52:23Z");
    }
}
