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
 * 金融界首页适配器单测（M18 T150，REQ-20260926-15 拍板一 #6）：真实截样本（2026-09-26 预检，www.jrj.com.cn 根首页——无参数
 * 列表路径合规注记，URL 内嵌 ddHHmm 分钟墙钟 + 尾号 externalId）的条目映射 + 结构漂移防御。零外呼。
 */
class JrjHomeAdapterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final JrjHomeAdapter adapter =
            new JrjHomeAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("jrj_home");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/jrj-home-sample.html");
    }

    @Test
    void parseList_mapsRealHomeAnchors_urlEmbeddedMinuteWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 12 条带题 yyyy/MM/ddHHmm{id} 条目（跨 finance/stock/bank 等频道子域）
        assertThat(items).hasSize(12);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("58539476");
        assertThat(first.title()).isEqualTo("中秋节后如何布局A股？");
        assertThat(first.url()).isEqualTo("https://stock.jrj.com.cn/2026/09/25131158539476.shtml");
        assertThat(first.author()).isEqualTo("金融界");
        // URL 内嵌 2026-09-25 13:11 墙钟（Asia/Shanghai）→ UTC 05:11
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-25T05:11:00Z"));
        // 目录声明 cursorType=NONE（首页编辑序，重复轮由唯一索引幂等吸收）
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("jrj_home");
    }
}
