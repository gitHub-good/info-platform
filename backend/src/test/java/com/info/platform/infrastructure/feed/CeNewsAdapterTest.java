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
 * 中国经济网滚动新闻适配器单测（M18 T152 条件席，REQ-20260926-15 补位序第 2 位顶替——和讯瑞数盾 FAIL）：真实截样本（2026-09-26 预检，www.ce.cn
 * 首页「滚动新闻」gdxw 块，URL 内嵌 t{yyyymmdd} 日粒度墙钟）的条目映射 + 结构漂移防御。零外呼。
 */
class CeNewsAdapterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final CeNewsAdapter adapter =
            new CeNewsAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("ce_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/ce-home-sample.html");
    }

    @Test
    void parseList_mapsRealRollBlock_urlEmbeddedDayWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 12 锚点中 11 条带题 gdxw 条目（限定滚动新闻块，生活/城市频道锚点天然隔离）
        assertThat(items).hasSize(11);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("3236586");
        assertThat(first.title()).contains("全国铁路客流继续保持高位运行");
        assertThat(first.url())
                .isEqualTo("http://www.ce.cn/xwzx/gnsz/gdxw/202609/t20260926_3236586.shtml");
        assertThat(first.author()).isEqualTo("中国经济网");
        // URL 内嵌 2026-09-26（日粒度北京零点）→ UTC 前一日 16:00
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-25T16:00:00Z"));
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("ce_news");
    }
}
