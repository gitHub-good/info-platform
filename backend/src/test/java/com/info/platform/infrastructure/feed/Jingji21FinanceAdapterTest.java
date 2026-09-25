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
 * 21 财经金融频道适配器单测（M14 T112，REQ 拍板一 #8）：真实截样本（2026-09-25 预检，21jingji.com/channel/finance； robots
 * 允许列表采集但显式禁 GPTBot 等 AI 训练爬虫——聚合展示用途不受影响，M15 管道前复核条款，ADR-0044）的条目映射 （article URL uuid 作
 * externalId/完整日期与「N分钟前」墙钟/全角分隔的日期+作者拆分）+ 结构漂移防御。零外呼。
 */
class Jingji21FinanceAdapterTest {

    /** 固定时钟：北京 2026-09-25 12:00（「30分钟前」折算基准）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"), ZoneId.of("UTC"));

    private final Jingji21FinanceAdapter adapter =
            new Jingji21FinanceAdapter(RestClient.builder().build(), CLOCK);

    private static InfoSource financeSource() {
        return PresetSources.fromCode("jingji21_finance");
    }

    private static String fixture() {
        return FeedFixtures.load("feed/jingji21-finance-sample.html");
    }

    @Test
    void parseList_mapsRealList_uuidIdDateAndCoAuthors() {
        List<RawFeedItem> items = adapter.parseList(fixture(), financeSource());

        // fixture 5 条实测样本（4 条完整日期 + 1 条相对时间）
        assertThat(items).hasSize(5);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("b9bf8b73c05b44064b75c5d3968464e0");
        assertThat(newest.title()).contains("隔夜逆回购").contains("1万亿");
        assertThat(newest.url())
                .isEqualTo(
                        "https://m.21jingji.com/article/20260924/herald/"
                                + "b9bf8b73c05b44064b75c5d3968464e0.html");
        // span「2026-09-24　　唐婧」→ 日期墙钟 + 作者拆分
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
        assertThat(newest.author()).isEqualTo("唐婧");
        assertThat(newest.cursorValue()).isNull();
    }

    @Test
    void parseList_relativeTimeToken_mapsAgainstClockWithDefaultAuthor() {
        List<RawFeedItem> items = adapter.parseList(fixture(), financeSource());

        // 尾条「30分钟前　　」（无作者位）→ 固定时钟北京 12:00 折算 11:30；author 回落站名
        RawFeedItem relative = items.get(4);
        assertThat(relative.title()).contains("期货公司业绩回暖");
        assertThat(relative.publishedAt()).isEqualTo(Instant.parse("2026-09-25T03:30:00Z"));
        assertThat(relative.author()).isEqualTo("21财经");
        // 该条 p 摘要为空 → null（不硬拼空串）
        assertThat(relative.summary()).isNull();
    }

    @Test
    void parseList_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parseList("<html><body></body></html>", financeSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移")
                .hasMessageContaining("jingji21_finance");
    }
}
