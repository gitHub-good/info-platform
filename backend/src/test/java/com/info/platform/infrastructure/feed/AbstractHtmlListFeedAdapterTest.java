package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 中文墙钟解析矩阵单测（M14 T111/T112 基类）：六源实测格式的全分支覆盖——相对时间（分/时/天）、昨天、绝对日期
 * （带/不带时间、斜杠分隔）、无年份日期（跨年回看上一年）、当天时间（零点后回看前一天）、不可识别回落 null。
 */
class AbstractHtmlListFeedAdapterTest {

    /** 固定时钟：北京 2027-01-02 12:00（跨年回看用例基准）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2027-01-02T04:00:00Z"), ZoneId.of("UTC"));

    @Test
    void parseChineseWallClock_relativeTokens_mapAgainstClockInstant() {
        Instant now = CLOCK.instant();
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("30分钟前", CLOCK))
                .isEqualTo(now.minusSeconds(30 * 60));
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("6小时前", CLOCK))
                .isEqualTo(now.minusSeconds(6 * 3600));
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("3天前", CLOCK))
                .isEqualTo(now.minusSeconds(3 * 86400));
    }

    @Test
    void parseChineseWallClock_absoluteTokens_mapShanghaiWallClock() {
        // 带时间：北京 2026-09-24 23:37 → UTC 15:37
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("2026-09-24 23:37", CLOCK))
                .isEqualTo(Instant.parse("2026-09-24T15:37:00Z"));
        // 不带时间：北京零点 → UTC 前一日 16:00
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("2026-09-24", CLOCK))
                .isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
        // 斜杠分隔（发改委栏式）
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("2026/09/21", CLOCK))
                .isEqualTo(Instant.parse("2026-09-20T16:00:00Z"));
    }

    @Test
    void parseChineseWallClock_monthDayToken_defaultsClockYearWithRollover() {
        // 当天与昨天（1 月 2 日时钟看 01-02/01-01）→ 时钟当年，北京零点
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("01-02", CLOCK))
                .isEqualTo(Instant.parse("2027-01-01T16:00:00Z"));
        // 未来时刻超 1 天（1 月 2 日看 12-30、01-05）→ 回看上一年（发布时间不可能晚于抓取）
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("12-30", CLOCK))
                .isEqualTo(Instant.parse("2026-12-29T16:00:00Z"));
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("01-05", CLOCK))
                .isEqualTo(Instant.parse("2026-01-04T16:00:00Z"));
        // 带时间（当天）
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("01-02 09:30", CLOCK))
                .isEqualTo(Instant.parse("2027-01-02T01:30:00Z"));
    }

    @Test
    void parseChineseWallClock_timeOnlyToken_rollbackAfterMidnight() {
        // 北京 12:00 抓「09:30」→ 当天
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("09:30", CLOCK))
                .isEqualTo(Instant.parse("2027-01-02T01:30:00Z"));
        // 未来时刻（北京 12:00 抓「23:50」）→ 回看前一天
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("23:50", CLOCK))
                .isEqualTo(Instant.parse("2027-01-01T15:50:00Z"));
    }

    @Test
    void parseChineseWallClock_yesterdayToken_mapsPreviousShanghaiDay() {
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("昨天 23:37", CLOCK))
                .isEqualTo(Instant.parse("2027-01-01T15:37:00Z"));
    }

    @Test
    void parseChineseWallClock_unrecognizableOrBlank_returnsNull() {
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock(null, CLOCK)).isNull();
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("  ", CLOCK)).isNull();
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("刚刚更新", CLOCK)).isNull();
        assertThat(AbstractHtmlListFeedAdapter.parseChineseWallClock("2026年9月24日", CLOCK)).isNull();
    }
}
