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
 * 人民网经济适配器单测（M17 T142，REQ-20260926-14 拍板一 #7）：真实截样本（2026-09-22 预检，finance.people.com.cn
 * 频道首页）的条目映射（/n1/ 锚点 + URL 内嵌日期墙钟 + c 栏目尾号作 externalId）+ GB 旧栏目与跨站链接隔离 + 结构漂移防御。 零外呼。
 */
class PeopleFinanceAdapterTest {

    private static final String ENDPOINT = "http://finance.people.com.cn/";

    private final PeopleFinanceAdapter adapter =
            new PeopleFinanceAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static InfoSource peopleSource() {
        return PresetSources.fromCode("people_finance");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/people-finance-sample.html");
    }

    @Test
    void parseList_mapsRealHomeAnchors_urlDateAndColumnTailAsExternalId() {
        List<RawFeedItem> items = adapter.parseList(fixture(), peopleSource());

        // fixture 首页 /n1/ 锚点 6 条真实条目（2026-09-22 预检）
        assertThat(items).hasSize(6);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("40805164");
        assertThat(newest.title()).contains("经略海洋");
        assertThat(newest.url())
                .isEqualTo("http://finance.people.com.cn/n1/2026/0924/c1004-40805164.html");
        assertThat(newest.author()).isEqualTo("人民网");
        // URL 内嵌日期 2026/0924（北京零点，日粒度）→ UTC 前一日 16:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
        // 目录声明 cursorType=NONE（日粒度 URL 日期 + 首页编辑序）：不产出游标
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_ignoresLegacyGbAndCrossSiteLinks() {
        List<RawFeedItem> items = adapter.parseList(fixture(), peopleSource());

        // /GB/ 旧栏目专题页与跨站链接（fangfei 子站）无 /n1/ 文章路径形态，不产出条目
        assertThat(items).extracting(RawFeedItem::url).allMatch(u -> u.contains("/n1/"));
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", peopleSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("people_finance");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        PeopleFinanceAdapter httpAdapter =
                new PeopleFinanceAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(peopleSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(6);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
