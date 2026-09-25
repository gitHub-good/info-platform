package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * RssFeedFetcher 单测（T101，方案 §4.3）：fixture XML 零外呼——标准 RSS 2.0 / Atom / 脏数据防御 / 游标止步与补抓截断 / headers
 * 透传（Mock server 拦截，不触真实网络）。
 */
class RssFeedFetcherTest {

    private static final String RSS_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>MarketWatch Top Stories</title>
              <item>
                <title>Fed signals slower path</title>
                <link>https://www.marketwatch.com/story/fed</link>
                <guid isPermaLink="false">mw-1002</guid>
                <pubDate>Mon, 21 Sep 2026 09:31:00 GMT</pubDate>
                <description><![CDATA[<p>Policymakers flagged a slower pace.</p>]]></description>
                <author>newsroom@marketwatch.com (MarketWatch)</author>
              </item>
              <item>
                <title>Stocks slip as yields rise</title>
                <link>https://www.marketwatch.com/story/yields</link>
                <guid isPermaLink="false">mw-1001</guid>
                <pubDate>Mon, 21 Sep 2026 08:00:00 GMT</pubDate>
                <description>Wall Street opened lower.</description>
              </item>
            </channel></rss>
            """;

    private static final String NUMERIC_GUID_RSS =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Flash</title>
              <item><title>较新快讯</title><link>https://example.com/f/2002</link>
                <guid>2002</guid><pubDate>Mon, 21 Sep 2026 09:31:00 GMT</pubDate></item>
              <item><title>较旧快讯</title><link>https://example.com/f/2001</link>
                <guid>2001</guid><pubDate>Mon, 21 Sep 2026 08:00:00 GMT</pubDate></item>
            </channel></rss>
            """;

    private static final String ATOM_XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Feed</title>
              <entry>
                <title>Atom entry title</title>
                <link href="https://example.com/atom/1" rel="alternate" type="text/html"/>
                <id>tag:example.com,2026:1</id>
                <updated>2026-09-21T07:00:00Z</updated>
                <summary>Atom summary text</summary>
              </entry>
            </feed>
            """;

    private final RssFeedFetcher fetcher = new RssFeedFetcher(RestClient.builder().build());

    private static InfoSource rssSource(CursorType cursorType) {
        return InfoSource.create(
                "t101_rss",
                "RSS 源",
                "国际",
                AdapterType.RSS,
                null,
                "https://example.com/rss.xml",
                new SourceConfig(
                        null, null, null, null, null, null, null, cursorType, "externalId", null),
                30,
                true,
                false);
    }

    @Test
    void fetch_standardRss20_mapsDefaultFields() {
        InfoSource source = rssSource(CursorType.NONE);

        List<RawFeedItem> items =
                fetcher.parse(RSS_XML, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(2);
        RawFeedItem newest = items.get(0);
        assertThat(newest.title()).isEqualTo("Fed signals slower path");
        assertThat(newest.url()).isEqualTo("https://www.marketwatch.com/story/fed");
        assertThat(newest.externalId()).isEqualTo("mw-1002");
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-21T09:31:00Z"));
        // CDATA 内 HTML 保留原文（strip_html 归映射层；RSS 默认映射只剥 XML 结构）
        assertThat(newest.summary()).contains("slower pace");
        assertThat(newest.author()).contains("MarketWatch");
        // 无 guid 时 externalId 回落 link（第二条验证 guid 优先，本条组内已覆盖）
        assertThat(items.get(1).externalId()).isEqualTo("mw-1001");
    }

    @Test
    void fetch_atomFeed_mapsLinkHrefAndUpdated() {
        InfoSource source = rssSource(CursorType.NONE);

        List<RawFeedItem> items =
                fetcher.parse(ATOM_XML, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(1);
        RawFeedItem item = items.get(0);
        assertThat(item.title()).isEqualTo("Atom entry title");
        assertThat(item.url()).isEqualTo("https://example.com/atom/1");
        assertThat(item.externalId()).isEqualTo("tag:example.com,2026:1");
        assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-09-21T07:00:00Z"));
        assertThat(item.summary()).isEqualTo("Atom summary text");
    }

    @Test
    void fetch_dirtyItems_degradedNotFatal() {
        String dirty =
                """
                <rss version="2.0"><channel>
                  <item><title>bad date</title><link>https://example.com/1</link>
                    <pubDate>not-a-date</pubDate></item>
                  <item><title>no link no guid</title></item>
                  <item><link>https://example.com/2</link><pubDate>Mon, 21 Sep 2026 09:31:00 GMT</pubDate></item>
                </channel></rss>
                """;
        InfoSource source = rssSource(CursorType.NONE);

        List<RawFeedItem> items =
                fetcher.parse(dirty, source, FetchContext.firstPage(null)).items();

        // 不可解析 pubDate → publishedAt null（缺时间指纹取抓取日，不致命）
        assertThat(items.get(0).publishedAt()).isNull();
        // 无 link/guid → externalId null（靠指纹兜底）
        assertThat(items.get(1).externalId()).isNull();
        // 无 title → 条目仍透传（title 过滤归摄取层 §4.2）
        assertThat(items.get(2).title()).isNull();
    }

    /** 脏数据路径直调解析（无 HTTP）：与 fetch 共用同一解析管线（包内可见）。 */
    private List<RawFeedItem> parseDirty(String xml, InfoSource source) {
        return fetcher.parse(xml, source, FetchContext.firstPage(null)).items();
    }

    @Test
    void fetch_nonRssStructure_throwsFeedFetchException() {
        String html = "<html><body><h1>Not a feed</h1></body></html>";
        InfoSource source = rssSource(CursorType.NONE);

        assertThatThrownBy(() -> parseDirty(html, source))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("t101_rss");
    }

    @Test
    void fetch_idCursor_stopsAtSeenItem() {
        InfoSource source = rssSource(CursorType.ID);

        // 已见数值游标 2001：仅返回更新的一条（2002），遇已见止（ID 数值比较）
        FetchResult result = fetcher.parse(NUMERIC_GUID_RSS, source, new FetchContext("2001", 1));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).externalId()).isEqualTo("2002");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void fetch_timeCursor_stopsAtSeenTime() {
        InfoSource source = rssSource(CursorType.TIME);

        // 已见 09:31:00Z：全部条目 ≤ 已见 → 空
        FetchResult result =
                fetcher.parse(RSS_XML, source, new FetchContext("2026-09-21T09:31:00Z", 1));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void fetch_backfillPagesNoSeenItem_marksTruncated() {
        InfoSource source = rssSource(CursorType.ID);

        // 深翻 3 页仍未遇到已见条目（游标 0 早于文档最旧条目）→ truncated（条目可能已滚出源侧窗口）
        FetchResult result = fetcher.parse(NUMERIC_GUID_RSS, source, new FetchContext("0", 3));

        assertThat(result.truncated()).isTrue();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    void fetch_httpGet_passesHeadersAndParsesBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.com/rss.xml"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "Mozilla/5.0 test"))
                .andExpect(header("Referer", "https://example.com"))
                .andRespond(withSuccess(RSS_XML, MediaType.APPLICATION_XML));
        RssFeedFetcher httpFetcher = new RssFeedFetcher(builder.build());
        InfoSource source =
                InfoSource.create(
                        "t101_http",
                        "HTTP 源",
                        "国际",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss.xml",
                        new SourceConfig(
                                null,
                                null,
                                null,
                                null,
                                Map.of(
                                        "User-Agent",
                                        "Mozilla/5.0 test",
                                        "Referer",
                                        "https://example.com"),
                                null,
                                null,
                                CursorType.NONE,
                                null,
                                null),
                        30,
                        true,
                        false);

        List<RawFeedItem> items = httpFetcher.fetch(source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(2);
        server.verify();
    }

    @Test
    void fetch_httpError_wrapsInFeedFetchException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.com/rss.xml"))
                .andRespond(
                        org.springframework.test.web.client.response.MockRestResponseCreators
                                .withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        RssFeedFetcher httpFetcher = new RssFeedFetcher(builder.build());
        InfoSource source = rssSource(CursorType.NONE);

        assertThatThrownBy(() -> httpFetcher.fetch(source, FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("t101_rss");
        server.verify();
    }
}
