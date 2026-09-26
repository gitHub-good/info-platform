package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 每日经济新闻首页适配器单测（M18 T151，REQ-20260926-15 拍板一 #3）：真实截样本（2026-09-26 预检，www.nbd.com.cn 首页
 * /articles/{yyyy-MM-dd}/{id}.html 锚点，URL 内嵌日粒度墙钟）的条目映射 + 结构漂移防御。零外呼。
 */
class NbdNewsAdapterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final NbdNewsAdapter adapter =
            new NbdNewsAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("nbd_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/nbd-home-sample.html");
    }

    @Test
    void parseList_mapsRealHomeAnchors_urlEmbeddedDayWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 16 锚点中 12 条带题（图片/缩略锚点无题被隔离）
        assertThat(items).hasSize(12);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("4592158");
        assertThat(first.title()).contains("梅卡曼德交出上市后首份");
        assertThat(first.url())
                .isEqualTo("https://www.nbd.com.cn/articles/2026-09-26/4592158.html");
        assertThat(first.author()).isEqualTo("每日经济新闻");
        // URL 内嵌 2026-09-26 日期（日粒度北京零点，沿人民网取舍）→ UTC 前一日 16:00
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-25T16:00:00Z"));
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("nbd_news");
    }
}
