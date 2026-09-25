package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 第一财经资讯适配器单测（M14 T112，REQ 拍板一 #7）：真实截样本（2026-09-25 预检，yicai.com/news 列表——旧快讯 API 已死走 HTML
 * 列表，robots 禁 /api//search 不涉列表，ADR-0044）的条目映射（news id 作 externalId/相对时间与 「昨天 HH:mm」以固定时钟折算墙钟）+
 * 结构漂移防御 + 取数管线。零外呼。
 */
class YicaiNewsAdapterTest {

    /** 固定时钟：北京 2026-09-25 12:00（相对时间折算基准）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"), ZoneId.of("UTC"));

    private static final String ENDPOINT = "https://www.yicai.com/news/";

    private final YicaiNewsAdapter adapter =
            new YicaiNewsAdapter(RestClient.builder().build(), CLOCK);

    private static InfoSource yicaiSource() {
        return PresetSources.fromCode("yicai_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/yicai-news-sample.html");
    }

    @Test
    void parseList_mapsRealList_relativeTimeAgainstFixedClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), yicaiSource());

        // fixture 5 条实测样本（ids newest-first，尾部实测存在乱序对 → cursorType=NONE 裁量见 ADR-0044）
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("103378345");
        assertThat(newest.title()).contains("最火中秋");
        assertThat(newest.url()).isEqualTo("https://www.yicai.com/news/103378345.html");
        assertThat(newest.author()).isEqualTo("第一财经");
        // 「1小时前」以固定时钟北京 12:00 折算 → 11:00 北京
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-25T03:00:00Z"));
        assertThat(newest.summary()).contains("客流提前出发");
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_yesterdayToken_mapsToPreviousShanghaiDay() {
        List<RawFeedItem> items = adapter.parseList(fixture(), yicaiSource());

        // fixture 第 4 条「昨天 23:37」→ 北京 2026-09-24 23:37
        assertThat(items.get(3).publishedAt()).isEqualTo(Instant.parse("2026-09-24T15:37:00Z"));
        // 尾条「昨天 20:55」→ 北京 2026-09-24 20:55
        assertThat(items.get(4).publishedAt()).isEqualTo(Instant.parse("2026-09-24T12:55:00Z"));
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", yicaiSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("yicai_news");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        YicaiNewsAdapter httpAdapter = new YicaiNewsAdapter(builder.build(), CLOCK);

        var result = httpAdapter.fetch(yicaiSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
