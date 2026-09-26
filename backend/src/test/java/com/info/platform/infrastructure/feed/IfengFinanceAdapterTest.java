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
 * 凤凰财经首页适配器单测（M18 T150，REQ-20260926-15 拍板一 #2）：真实截样本（2026-09-26 预检，finance.ifeng.com 首页 /c/{base62}
 * 锚点——旧 api 已弃用走 HTML）的条目映射 + 结构漂移防御。零外呼。
 */
class IfengFinanceAdapterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final IfengFinanceAdapter adapter =
            new IfengFinanceAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("ifeng_finance");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/ifeng-finance-sample.html");
    }

    @Test
    void parseList_mapsRealHomeAnchors_base62HashAsExternalId() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 12 条带题 /c/ 条目
        assertThat(items).hasSize(12);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("8wj7YWTiFiX");
        assertThat(first.title()).contains("丰田买中国零件");
        assertThat(first.url()).isEqualTo("https://finance.ifeng.com/c/8wj7YWTiFiX");
        assertThat(first.author()).isEqualTo("凤凰财经");
        // 首页无显式时间：发布时间回落摄取时刻；cursorType=NONE
        assertThat(first.publishedAt()).isNull();
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("ifeng_finance");
    }
}
