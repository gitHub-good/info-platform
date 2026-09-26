package com.info.platform.application.recommendation;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 推荐事件消费 Job（M16 T133，ADR-0051 裁决 2）：第 14 个受管任务——{@code RECOMMENDATION_FEED} FIXED_DELAY tick（缺省
 * 60s，{@code job.RECOMMENDATION_FEED} 可配 30s~5min，页面热切换）。扫未消费事件（LEFT JOIN recommendation_card 去重 +
 * 20s 落库缓冲 + 24h 补跑窗）→ 每用户三级关联 → 卡片生成（LLM/白名单/模板兜底）→ 推送闸门（降频/排序/日上限/SILENT 留痕）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（十四 Job 惯例）；链路逻辑由单测直调 tick 验证（LLM 全 Mock 零外呼）。 本类只做
 * tick 入口与 JobRunStats 上报。
 */
@Component
public class RecommendationFeedJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final RecommendationFeedService feedService;

    public RecommendationFeedJob(RecommendationFeedService feedService) {
        this.feedService = feedService;
    }

    @Override
    public String jobKey() {
        return "RECOMMENDATION_FEED";
    }

    @Override
    public String displayName() {
        return "推荐事件消费";
    }

    @Override
    public String description() {
        return "推荐链路消费 tick（默认 60s）：扫 24h 内未消费事件（20s 落库缓冲 + LEFT JOIN 卡表去重）→ 每用户三级关联"
                + "→ 可解释卡片生成（LLM 仅语言组织 + 白名单校验拒即模板）→ 推送闸门（降频/综合分排序/日上限 10，"
                + "超限与降频建卡但 SILENT 静默留痕不弹 SSE——M16 ADR-0051 裁决 2/3）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口：委托一个 tick；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        RecommendationFeedService.FeedReport report = feedService.tick();
        lastProcessedCount = report.processed();
        lastRunDetail = report.detail();
        log.info("推荐消费 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
    }

    @Override
    public int lastProcessedCount() {
        return lastProcessedCount;
    }

    @Override
    public String lastRunDetail() {
        return lastRunDetail;
    }
}
