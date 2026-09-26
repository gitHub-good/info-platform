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
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 网易财经首页适配器单测（M18 T150，REQ-20260926-15 拍板一 #1）：真实截样本（2026-09-26 预检，money.163.com 首页 /dy/article/
 * 锚点块）的条目映射（网易号文章码 externalId/无显式时间 → 发布时间回落摄取时刻）+ 结构漂移防御。零外呼。
 */
class NeteaseMoneyAdapterTest {

    private static final String ENDPOINT = "https://money.163.com/";

    /** 固定时钟（发布时间回落口径的可测基准，本源无相对时间折算）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final NeteaseMoneyAdapter adapter =
            new NeteaseMoneyAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("netease_money");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/netease-money-sample.html");
    }

    @Test
    void parseList_mapsRealHomeAnchors_dyArticleCodeAsExternalId() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 12 条带题 /dy/article/ 条目（图片锚点无题被隔离，同码克隆去重）
        assertThat(items).hasSize(12);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("L7N9737F05198NMR");
        assertThat(first.title()).contains("盖茨");
        assertThat(first.url()).isEqualTo("https://www.163.com/dy/article/L7N9737F05198NMR.html");
        assertThat(first.author()).isEqualTo("网易财经");
        // 首页列表无显式时间：发布时间 null 回落抓取时刻（摄取层口径），目录声明 cursorType=NONE
        assertThat(first.publishedAt()).isNull();
        assertThat(first.cursorValue()).isNull();
        assertThat(items).extracting(RawFeedItem::externalId).doesNotHaveDuplicates();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("netease_money");
    }

    @Test
    void fetch_successPath_itemsReturned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        NeteaseMoneyAdapter httpAdapter = new NeteaseMoneyAdapter(builder.build(), FIXED_CLOCK);

        var result = httpAdapter.fetch(source(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(12);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
