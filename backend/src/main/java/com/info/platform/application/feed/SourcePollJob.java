package com.info.platform.application.feed;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 资讯源轮询 Job（M13 T103，ADR-0040）：第 8 个收编任务——{@code SOURCE_POLL} 聚合 FIXED_DELAY tick（默认 60s， {@code
 * job.SOURCE_POLL} 可配 15s~300s，页面热切换）。源维度调度（到期/错峰/退避/补抓）在 {@link SourceSchedulingService}， 本类只做
 * tick 入口与 JobRunStats 上报（tick 明细 {@code dispatched=n; ok=n; fail=n} 段式，ADR-0036）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（八 Job 惯例）；轮询逻辑由单测直调 tick/poll 验证（零外呼）。
 */
@Component
public class SourcePollJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(SourcePollJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final SourceSchedulingService schedulingService;

    public SourcePollJob(SourceSchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @Override
    public String jobKey() {
        return "SOURCE_POLL";
    }

    @Override
    public String displayName() {
        return "资讯源轮询";
    }

    @Override
    public String description() {
        return "聚合轮询全部启用资讯源（默认 60s tick）：拉取 → 白名单映射 → 跨源指纹去重 → 落库 → 游标推进；"
                + "失败指数退避、断流深翻补抓（M13 源接入框架，ADR-0040）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    /** 定时与手动触发共用入口：委托一个 tick；轮首重置统计（失败轮不误报上一轮）。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        SourceSchedulingService.TickReport report = schedulingService.tick();
        lastProcessedCount = report.dispatched();
        lastRunDetail = report.detail();
        log.info("资讯源轮询 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
