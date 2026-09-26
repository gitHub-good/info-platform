package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 通用 RSS 示例包双源 fixture 单测（M18 T152，REQ-20260926-15 条目 6：默认停用播种不计 30 口径）：WSJ WorldNews 与 IT之家目录配置对
 * 真实响应截样本（各 3 条，2026-09-26 可达性实测锁定）的解析正确性（RSS 2.0 默认映射/pubDate/TIME 游标沿 MarketWatch T106
 * 先例）。用户启用即按通用源语义管理。零外呼。
 */
class RssFeedFetcherBatchThreeTest {

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
    void parse_exampleWsjWorld_rssDefaultsWithTimeCursor() {
        InfoSource source = catalogSource("example_wsj_world");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/wsj-world-sample.xml"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实截样本 3 条（2026-09-26 UTC 国际要闻），guid 作 externalId
        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.title()).contains("Russia Tells Businesses");
        assertThat(first.url()).startsWith("https://www.wsj.com/world/");
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-26T02:00:00Z"));
        assertThat(first.cursorValue()).isEqualTo("2026-09-26T02:00:00Z");
    }

    @Test
    void parse_exampleIthome_rssDefaultsWithTimeCursor() {
        InfoSource source = catalogSource("example_ithome");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/ithome-sample.xml"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实截样本 3 条（2026-09-26 UTC 科技资讯），无 guid 时 link 作 externalId（引擎缺省口径）
        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.title()).contains("华为 LOGO 印上大疆云台相机");
        assertThat(first.url()).isEqualTo("https://www.ithome.com/1/007/357.htm");
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-26T06:24:19Z"));
        assertThat(first.cursorValue()).isEqualTo("2026-09-26T06:24:19Z");
    }
}
