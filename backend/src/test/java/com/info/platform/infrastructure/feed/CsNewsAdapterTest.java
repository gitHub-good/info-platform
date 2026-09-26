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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 中证网要闻适配器单测（M17 T141，REQ-20260926-14 拍板一 #6）：真实截样本（2026-09-22 预检，cs.com.cn 首页 7×24
 * 快讯块—— 栏目列表页均为 JS 模板渲染，首页块为唯一新鲜 SSR 窗口）的条目映射（em 时分 + URL 内嵌日期拼合墙钟/detail 尾号作
 * externalId/title 属性标题）+ 旧文区块隔离 + 结构漂移防御。零外呼。
 */
class CsNewsAdapterTest {

    private static final String ENDPOINT = "https://www.cs.com.cn/";

    private final CsNewsAdapter adapter =
            new CsNewsAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static InfoSource csSource() {
        return PresetSources.fromCode("cs_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/cs-home-sample.html");
    }

    @Test
    void parseList_mapsRealFlashBlock_emTimePlusUrlDate() {
        List<RawFeedItem> items = adapter.parseList(fixture(), csSource());

        // fixture 7×24 块 6 条真实条目（2026-09-22 预检）
        assertThat(items).hasSize(6);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("2026092610041829");
        assertThat(newest.title()).contains("大丰实业").contains("中标");
        assertThat(newest.url())
                .isEqualTo(
                        "https://www.cs.com.cn/ssgs/01/2026/09/26/detail_2026092610041829.html");
        assertThat(newest.author()).isEqualTo("中证网");
        // em 09:47 + URL 日期 2026/09/26（北京墙钟）→ UTC 前一日 01:47
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-26T01:47:00Z"));
        // 目录声明 cursorType=NONE：不产出游标
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_ignoresNonFlashBlocksOutsideEmList() {
        List<RawFeedItem> items = adapter.parseList(fixture(), csSource());

        // 旧文区块噪音（2026-07-11 detail 链接、无 em 时分）不在 7×24 块内，不产出条目
        assertThat(items)
                .extracting(RawFeedItem::externalId)
                .doesNotContain("2026071110023356");
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", csSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("cs_news");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        CsNewsAdapter httpAdapter = new CsNewsAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(csSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(6);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
