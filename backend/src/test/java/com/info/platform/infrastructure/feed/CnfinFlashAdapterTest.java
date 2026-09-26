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
 * 新华财经首页快讯块适配器单测（M18 T151，REQ-20260926-15 拍板一 #4）：真实截样本（2026-09-26 预检，www.cnfin.com 首页「新华快讯」
 * 块——锚点文本 HH:mm 前缀 + URL 内嵌 yyyymmdd 日期拼合分钟精度墙钟，沿中证网 7×24 块先例）的条目映射 + 结构漂移防御。零外呼。
 */
class CnfinFlashAdapterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final CnfinFlashAdapter adapter =
            new CnfinFlashAdapter(RestClient.builder().build(), FIXED_CLOCK);

    private static InfoSource source() {
        return PresetSources.fromCode("cnfin_flash");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/cnfin-home-sample.html");
    }

    @Test
    void parseList_mapsRealFlashBlock_minutePrecisionFromPrefixAndUrl() {
        List<RawFeedItem> items = adapter.parseList(fixture(), source());

        // fixture 10 条快讯（全带题，HH:mm 前缀剥离后成题）
        assertThat(items).hasSize(10);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("4475194");
        // 锚点文本「11:32欧盟统计局数据显示…」→ 标题剥离 HH:mm 前缀
        assertThat(first.title()).startsWith("欧盟统计局数据显示").doesNotContain("11:32");
        assertThat(first.url())
                .isEqualTo("https://www.cnfin.com/kx/detail/20260926/4475194_1.html");
        assertThat(first.author()).isEqualTo("新华财经");
        // URL 日期 2026-09-26 + 前缀 11:32（Asia/Shanghai）→ UTC 03:32
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-26T03:32:00Z"));
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parseList_titleWithoutTimePrefix_fallsBackToDateMidnight() {
        // 锚点文本无 HH:mm 前缀（结构容错）：发布时间回落 URL 日期北京零点，标题原样
        String body =
                "<html><body><a href='//www.cnfin.com/kx/detail/20260926/4475194_1.html'>"
                        + "欧盟统计局数据显示通胀上行</a></body></html>";
        List<RawFeedItem> items = adapter.parseList(body, source());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("欧盟统计局数据显示通胀上行");
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-25T16:00:00Z"));
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", source()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("cnfin_flash");
    }
}
