package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 财政部政策发布适配器单测（M17 T140，REQ-20260926-14 拍板一 #2）：真实截样本（2026-09-22 预检，szs.mof.gov.cn
 * 税政司政策发布列表现行结构）的条目映射（title 属性标题/相对链接绝对化/URL 尾号作 externalId/span 日期墙钟）+ 结构漂移防御。
 * 零外呼。
 */
class MofPolicyAdapterTest {

    private final MofPolicyAdapter adapter =
            new MofPolicyAdapter(RestClient.builder().build(), Clock.systemUTC());

    private static com.info.platform.domain.feed.InfoSource mofSource() {
        return PresetSources.fromCode("mof_policy");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/mof-policy-sample.html");
    }

    @Test
    void parseList_mapsRealList_titleLinkDateAndUrlTailAsExternalId() {
        List<RawFeedItem> items = adapter.parseList(fixture(), mofSource());

        // fixture 首屏 5 条实测样本（2026-09-22 预检，均为财税政策公告）
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("3996707");
        assertThat(newest.title()).contains("境内单位代扣代缴自然人增值税管理办法");
        assertThat(newest.url())
                .isEqualTo("https://szs.mof.gov.cn/zhengcefabu/202609/t20260904_3996707.htm");
        assertThat(newest.author()).isEqualTo("财政部");
        // span 墙钟 2026-09-04（北京零点）→ UTC 前一日 16:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-03T16:00:00Z"));
        // 目录声明 cursorType=NONE（日粒度墙钟，裁量沿 ADR-0044 官方源先例）
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", mofSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("mof_policy");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(mofSource().getEndpoint()))
                .andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        MofPolicyAdapter httpAdapter = new MofPolicyAdapter(builder.build(), Clock.systemUTC());

        var result = httpAdapter.fetch(mofSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(5);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
