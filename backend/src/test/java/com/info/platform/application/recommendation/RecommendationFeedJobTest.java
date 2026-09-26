package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.jobrun.ScheduleType;
import org.junit.jupiter.api.Test;

/**
 * RECOMMENDATION_FEED Job 契约单测（T133，第 14 键，方案 §4.9 / ADR-0051 裁决 2）：jobKey/展示名/FIXED_DELAY、tick 委托与
 * JobRunStats 留痕（processed + detail 透传）。测试 profile 种子 enabled=false 调度零注册（十四 Job 惯例）。
 */
class RecommendationFeedJobTest {

    @Test
    void jobContract_fourteenthKey_fixedDelay() {
        // Arrange
        RecommendationFeedJob job =
                new RecommendationFeedJob(mock(RecommendationFeedService.class));

        // Assert：第 14 键、FIXED_DELAY 60s 形态（间隔值在 job.RECOMMENDATION_FEED 种子/yml）
        assertThat(job.jobKey()).isEqualTo("RECOMMENDATION_FEED");
        assertThat(job.displayName()).isEqualTo("推荐事件消费");
        assertThat(job.scheduleType()).isEqualTo(ScheduleType.FIXED_DELAY);
        assertThat(job.description()).contains("60s").contains("推送闸门");
    }

    @Test
    void run_delegatesTick_andReportsStats() {
        // Arrange
        RecommendationFeedService feedService = mock(RecommendationFeedService.class);
        when(feedService.tick())
                .thenReturn(
                        new RecommendationFeedService.FeedReport(
                                "feed=scan:2; trig:2; hit:1; card=llm:1/tpl:0; push:1; mute:0; quota:0",
                                2));
        RecommendationFeedJob job = new RecommendationFeedJob(feedService);

        // Act
        job.run();

        // Assert：轮首重置后透传 tick 报告（JobRunStats 消费）
        assertThat(job.lastProcessedCount()).isEqualTo(2);
        assertThat(job.lastRunDetail()).startsWith("feed=scan:2");
    }
}
