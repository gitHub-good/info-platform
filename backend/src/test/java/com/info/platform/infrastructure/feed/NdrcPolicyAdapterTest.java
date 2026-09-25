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
 * 发改委政策发布适配器单测（M14 T111，REQ 拍板一 #4）：真实截样本（2026-09-25 预检，xxgk/zcfb/fzggwl 政策发布现行 入口）的 条目映射（标题 title
 * 属性/相对链接绝对化/URL 作 externalId/日期墙钟）+ 解读跨栏条目保留 + 结构漂移防御。 零外呼。
 */
class NdrcPolicyAdapterTest {

    private static final String ENDPOINT = "https://www.ndrc.gov.cn/xxgk/zcfb/fzggwl/";

    private final NdrcPolicyAdapter adapter =
            new NdrcPolicyAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static InfoSource ndrcSource() {
        return PresetSources.fromCode("ndrc_policy");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/ndrc-policy-sample.html");
    }

    @Test
    void parseList_mapsRealList_titleLinkDateAndUrlAsExternalId() {
        List<RawFeedItem> items = adapter.parseList(fixture(), ndrcSource());

        // fixture 首屏 5 条实测样本（2 条令 + 1 条令 + 1 条令 + 1 条解读）
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId())
                .isEqualTo(
                        "https://www.ndrc.gov.cn/xxgk/zcfb/fzggwl/202609/t20260921_1407733.html");
        assertThat(newest.title()).contains("粮油仓储物流设施保护办法").contains("2026年第46号");
        assertThat(newest.url()).isEqualTo(newest.externalId());
        assertThat(newest.author()).isEqualTo("国家发展改革委");
        // span 墙钟 2026/09/21（北京零点）→ UTC 前一日 16:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-20T16:00:00Z"));
        // 目录声明 cursorType=NONE（日期粒度过粗，裁量见 ADR-0044）：不产出游标
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_keepsInterpretationCrossLinks() {
        List<RawFeedItem> items = adapter.parseList(fixture(), ndrcSource());

        // 解读条目（../../jd/jd/ 相对链接）与令同列表呈现，按栏内内容保留，相对路径正确绝对化
        RawFeedItem interpretation = items.get(4);
        assertThat(interpretation.externalId())
                .startsWith("https://www.ndrc.gov.cn/xxgk/jd/jd/")
                .contains("t20260716_1406532.html");
        assertThat(interpretation.title()).contains("答记者问");
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", ndrcSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("ndrc_policy");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        NdrcPolicyAdapter httpAdapter = new NdrcPolicyAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(ndrcSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
