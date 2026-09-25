package com.info.platform.application.analysis;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 归类管道 Job（M15 T121，ADR-0046 裁决 4）：第 9 个收编任务——{@code NEWS_PIPELINE} 聚合 FIXED_DELAY tick（默认 10min
 * 批窗口，{@code job.NEWS_PIPELINE} 可配 5~15min，页面热切换）。 L0 预筛 → L1 批量归类（→ L2 配额提取随 T122 追加段；
 * 段间独立容错，一段失败不阻断后段与下轮）。本类只做 tick 入口与 JobRunStats 上报。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（八 Job 惯例）；管道逻辑由单测直调 tick 验证（LLM 全 Mock 零外呼）。
 */
@Component
public class NewsPipelineJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(NewsPipelineJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final NewsPipelineService pipelineService;

    public NewsPipelineJob(NewsPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @Override
    public String jobKey() {
        return "NEWS_PIPELINE";
    }

    @Override
    public String displayName() {
        return "AI 归类管道";
    }

    @Override
    public String description() {
        return "批窗口（默认 10min）：L0 规则预筛（noise 隔离 + 近重复关联主条，零成本）→ L1 批量归类（35 枚举，"
                + "≤20 条/批 + 对半拆批 + 低置信兜底）→ L2 事件提取（T122 追加）；段间独立容错（M15 ADR-0046）";
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
        NewsPipelineService.TickReport report = pipelineService.tick();
        lastProcessedCount = report.processed();
        lastRunDetail = report.detail();
        log.info("AI 归类管道 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
