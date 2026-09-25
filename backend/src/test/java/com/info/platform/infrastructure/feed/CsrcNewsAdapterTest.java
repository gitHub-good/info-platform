package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 证监会要闻适配器单测（M14 T111，REQ 拍板一 #5）：真实截样本（2026-09-25 预检，首页要闻 tab 块——普查 common_list.shtml 端点实测冻结于
 * 2021-12，现行结构对照结论见 ADR-0044）的 条目映射（c100028 栏目锚定/置顶跨栏条目 排除/MM-dd 无年份日期补当年）+ 结构漂移防御。零外呼。
 */
class CsrcNewsAdapterTest {

    /** 固定时钟：北京 2026-09-25 12:00（无年份日期「09-18」补当年 2026）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"), ZoneId.of("UTC"));

    private static final String ENDPOINT = "https://www.csrc.gov.cn/";

    private final CsrcNewsAdapter adapter =
            new CsrcNewsAdapter(RestClient.builder().build(), CLOCK);

    private static InfoSource csrcSource() {
        return PresetSources.fromCode("csrc_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/csrc-home-sample.html");
    }

    @Test
    void parseList_mapsHeadlineBlock_scopedToC100028Anchors() {
        List<RawFeedItem> items = adapter.parseList(fixture(), csrcSource());

        // fixture：置顶 li.first（c106311 领导活动跨栏）+ 5 条 c100028 要闻（li.li-height）→ 栏目锚定后 5 条
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("c7659506");
        assertThat(newest.title()).contains("吹哨人").contains("17起案件线索");
        assertThat(newest.url())
                .isEqualTo("https://www.csrc.gov.cn/csrc/c100028/c7659506/content.shtml");
        assertThat(newest.author()).isEqualTo("中国证监会");
        // 首页块时间为 MM-dd（无年份）：按固定时钟当年 2026-09-18 北京零点 → UTC 前一日 16:00
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-17T16:00:00Z"));
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_excludesFeaturedCrossColumnItem() {
        List<RawFeedItem> items = adapter.parseList(fixture(), csrcSource());

        // 置顶 li.first 指向 c106311（领导活动栏），非要闻栏目内容，href 锚定排除
        assertThat(items).extracting(RawFeedItem::externalId).doesNotContain("c7657131");
        assertThat(items).noneMatch(i -> i.url().contains("/c106311/"));
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(
                        () ->
                                adapter.parseList(
                                        "<html><body><ul></ul></body></html>", csrcSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("csrc_news");
    }
}
