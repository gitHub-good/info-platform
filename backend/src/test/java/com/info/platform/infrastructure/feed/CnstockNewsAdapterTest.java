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
 * 上证报要闻适配器单测（M17 T141，REQ-20260926-14 拍板一 #5）：真实截样本（2026-09-22 预检，cnstock 首页要闻块—— 旧 news 子域 302 进新站
 * /channel，列表页为客户端渲染，首页块为唯一新鲜 SSR 窗口，沿证监会首页块先例）的条目映射（CSS-module 哈希类锚定/相对时间与 MM-dd 混排墙钟/轮播克隆去重/topic
 * 链接隔离）+ 结构漂移防御。零外呼。
 */
class CnstockNewsAdapterTest {

    private static final String ENDPOINT = "https://www.cnstock.com/";

    /** 固定时钟 2026-09-26 12:00Z（北京 20:00）：相对时间折算与 MM-dd 年份推断的可测基准。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final CnstockNewsAdapter adapter =
            new CnstockNewsAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource cnstockSource() {
        return PresetSources.fromCode("cnstock_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/cnstock-home-sample.html");
    }

    @Test
    void parseList_mapsRealHomeBlock_relativeTimeAndMonthDayWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), cnstockSource());

        // fixture 首页要闻块 10 条真实条目（轮播克隆与专题链接被隔离/去重）
        assertThat(items).hasSize(10);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("795734");
        assertThat(newest.title()).isEqualTo("苹果，逼近5万亿美元");
        assertThat(newest.url()).isEqualTo("https://www.cnstock.com/commonDetail/795734");
        assertThat(newest.author()).isEqualTo("上海证券报");
        // 相对墙钟「1小时前」以注入时钟折算（2026-09-26 12:00 UTC → 11:00 UTC）
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-26T11:00:00Z"));
        // 目录声明 cursorType=NONE（相对时间折算 + 编辑序）：不产出游标
        assertThat(newest.cursorValue()).isNull();
        // 末条为 MM-dd 无年份格式（09-24，年份取时钟当年 2026，北京零点）
        assertThat(items.get(9).publishedAt()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
    }

    @Test
    void parseList_dedupesCarouselClones_andIgnoresTopicLinks() {
        List<RawFeedItem> items = adapter.parseList(fixture(), cnstockSource());

        // 首条轮播克隆（同 id 795734 出现两次）去重后仅一条；专题链接（topicDetail）不产出条目
        assertThat(items).extracting(RawFeedItem::externalId).containsOnlyOnce("795734");
        assertThat(items).hasSize(10);
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", cnstockSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("cnstock_news");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(fixture(), MediaType.TEXT_HTML));
        CnstockNewsAdapter httpAdapter = new CnstockNewsAdapter(builder.build(), FIXED_CLOCK);

        var result = httpAdapter.fetch(cnstockSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(10);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
