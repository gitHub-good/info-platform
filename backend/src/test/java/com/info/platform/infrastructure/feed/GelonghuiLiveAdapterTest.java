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
 * 格隆汇快讯 Nuxt payload 适配器单测（M18 T152 竞争席，REQ-20260926-15 拍板二四验全过）：真实截样本（2026-09-26 预检， /live/ SSR
 * {@code window.__NUXT__} payload——IIFE 参数绑定解析：{@code function(a..y){return {...}}(args)} 尾参表解
 * createTimestamp epoch 秒，title/route(直链，{\u002F} 转义还原)逐条抽取）的条目映射 + 结构漂移防御。零外呼。
 */
class GelonghuiLiveAdapterTest {

    private static final String ENDPOINT = "https://www.gelonghui.com/live/";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final GelonghuiLiveAdapter adapter =
            new GelonghuiLiveAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("gelonghui_live");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/gelonghui-live-sample.html");
    }

    @Test
    void parseList_mapsRealNuxtPayload_iifeBindingResolved() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 13 条快讯（真实 payload：id 递减 newest-first）
        assertThat(items).hasSize(13);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("2689948");
        assertThat(first.title()).isEqualTo("2026民洽会集中签约 项目总金额达269.29亿元");
        // route 字段 \u002F 转义还原为直链
        assertThat(first.url()).isEqualTo("https://www.gelonghui.com/live/2689948");
        assertThat(first.author()).isEqualTo("格隆汇");
        // createTimestamp 经 IIFE 尾参绑定解析：j → 1790402884（epoch 秒，UTC 06:08:04）
        assertThat(first.publishedAt()).isEqualTo(Instant.ofEpochSecond(1790402884L));
        // 目录声明 cursorType=NONE（重复轮由唯一索引幂等吸收）
        assertThat(first.cursorValue()).isNull();
        assertThat(items).extracting(RawFeedItem::externalId).doesNotHaveDuplicates();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("gelonghui_live");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        GelonghuiLiveAdapter httpAdapter = new GelonghuiLiveAdapter(builder.build(), FIXED_CLOCK);

        var result = httpAdapter.fetch(source(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(13);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
