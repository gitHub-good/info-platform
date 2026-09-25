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
 * 证券时报要闻适配器单测（M14 T112，REQ 拍板一 #6）：真实截样本（2026-09-25 预检，article/list/yw.html 现行栏目路径 ——普查旧快讯路径 404
 * 的落地对照，ADR-0044）的条目映射（detail id 作 externalId/首 span 媒体名作 author/末 span HH:mm 当天墙钟 + 零点后回看前一天）+
 * 结构漂移防御。零外呼。
 */
class StcnNewsAdapterTest {

    /** 固定时钟：北京 2026-09-25 12:00（「11:11」落当天；02:00Z 用例验证零点回看）。 */
    private static final Clock NOON =
            Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"), ZoneId.of("UTC"));

    private static final Clock EARLY_MORNING =
            Clock.fixed(Instant.parse("2026-09-25T01:00:00Z"), ZoneId.of("UTC"));

    private final StcnNewsAdapter adapter = new StcnNewsAdapter(RestClient.builder().build(), NOON);

    private static InfoSource stcnSource() {
        return PresetSources.fromCode("stcn_news");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/stcn-yw-sample.html");
    }

    @Test
    void parseList_mapsRealList_detailIdAuthorAndTodayWallClock() {
        List<RawFeedItem> items = adapter.parseList(fixture(), stcnSource());

        // fixture 首屏 4 条实测样本
        assertThat(items).hasSize(4);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("4199211");
        assertThat(newest.title()).contains("香港证监会").contains("三大行动目标");
        assertThat(newest.url()).isEqualTo("https://www.stcn.com/article/detail/4199211.html");
        // info 首 span 媒体名（券商中国）、末 span 时间（11:11 → 当天北京）
        assertThat(newest.author()).isEqualTo("券商中国");
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-25T03:11:00Z"));
        assertThat(newest.summary()).contains("香港证监会公布支持落实");
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_infoSpansWithoutAuthor_authorFallsBackToMediaSpan() {
        List<RawFeedItem> items = adapter.parseList(fixture(), stcnSource());

        // 第二条 info 仅两 span（媒体名 + 时间，无作者位）：author=媒体名，时间仍取末 span
        RawFeedItem second = items.get(1);
        assertThat(second.author()).isEqualTo("人民日报客户端");
        assertThat(second.publishedAt()).isEqualTo(Instant.parse("2026-09-25T02:47:00Z"));
    }

    @Test
    void parseList_timeOnlyAfterMidnight_rollbacksToPreviousDay() {
        // 北京 09:00 抓到「11:11」→ 未来时刻回看前一天（零点后抓昨夜条目的边界）
        StcnNewsAdapter earlyAdapter =
                new StcnNewsAdapter(RestClient.builder().build(), EARLY_MORNING);

        List<RawFeedItem> items = earlyAdapter.parseList(fixture(), stcnSource());

        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-24T03:11:00Z"));
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", stcnSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("stcn_news");
    }
}
