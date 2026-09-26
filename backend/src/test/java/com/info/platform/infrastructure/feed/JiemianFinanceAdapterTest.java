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
 * 界面新闻财经适配器单测（M17 T141，REQ-20260926-14 拍板一 #4）：真实截样本（2026-09-22 预检，lists/800 财经频道—— REQ
 * 记 lists/2 实测为商业频道，预检修正）的条目映射（卡片标题/摘要/作者/MM-dd HH:mm 墙钟/article id 作 externalId）+ 右栏噪音隔离 +
 * 结构漂移防御。零外呼。
 */
class JiemianFinanceAdapterTest {

    private static final String ENDPOINT = "https://www.jiemian.com/lists/800.html";

    private final JiemianFinanceAdapter adapter =
            new JiemianFinanceAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static InfoSource jiemianSource() {
        return PresetSources.fromCode("jiemian_finance");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/jiemian-finance-sample.html");
    }

    @Test
    void parseList_mapsRealCards_titleSummaryAuthorAndMonthDayWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), jiemianSource());

        // fixture 主列表 4 张真实卡片（2026-09-22 预检）
        assertThat(items).hasSize(4);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("15138868");
        assertThat(newest.title()).contains("宏观晚6点");
        assertThat(newest.summary()).contains("宏观要闻");
        assertThat(newest.author()).isEqualTo("辛圆");
        assertThat(newest.url()).isEqualTo("https://www.jiemian.com/article/15138868.html");
        // news-footer__date 墙钟 09/24 18:00（北京时间）→ UTC 10:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
        // 目录声明 cursorType=NONE（编辑序非时间序）：不产出游标
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_ignoresSidebarFlashBlockOutsideMainList() {
        List<RawFeedItem> items = adapter.parseList(fixture(), jiemianSource());

        // 右栏快讯噪音（article/15140848）不在 #load-list 主列表内，不产出条目
        assertThat(items)
                .extracting(RawFeedItem::externalId)
                .doesNotContain("15140848");
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", jiemianSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("jiemian_finance");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        JiemianFinanceAdapter httpAdapter =
                new JiemianFinanceAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(jiemianSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(4);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
