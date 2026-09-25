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
 * 统计局最新发布适配器单测（M14 T111，REQ 拍板一 #9）：真实截样本（2026-09-25 预检，sj/zxfb 最新发布栏）的条目映射 （响应式三锚点取首个/标题 title
 * 属性/URL 作 externalId/日期墙钟）+ 结构漂移防御 + 取数管线。零外呼。
 */
class StatsGovReleaseAdapterTest {

    private static final String ENDPOINT = "https://www.stats.gov.cn/sj/zxfb/";

    private final StatsGovReleaseAdapter adapter =
            new StatsGovReleaseAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static InfoSource statsSource() {
        return PresetSources.fromCode("stats_release");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/stats-release-sample.html");
    }

    @Test
    void parseList_mapsRealList_singleAnchorPerItem() {
        List<RawFeedItem> items = adapter.parseList(fixture(), statsSource());

        // fixture 首屏 4 条实测样本（每 li 含 pc_1600/mhide/pchide 三个响应式锚点，仅取首个不重复）
        assertThat(items).hasSize(4);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId())
                .isEqualTo("https://www.stats.gov.cn/sj/zxfb/202609/t20260923_1965403.html");
        assertThat(newest.title()).isEqualTo("2026年9月中旬流通领域重要生产资料市场价格变动情况");
        assertThat(newest.url()).isEqualTo(newest.externalId());
        assertThat(newest.author()).isEqualTo("国家统计局");
        // span 墙钟 2026-09-24（北京零点）→ UTC 前一日 16:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", statsSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("stats_release");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        StatsGovReleaseAdapter httpAdapter =
                new StatsGovReleaseAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(statsSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(4);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
