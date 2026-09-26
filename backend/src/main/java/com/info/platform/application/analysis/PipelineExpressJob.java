package com.info.platform.application.analysis;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 高价值快速通道 Job（M16 T131，ADR-0051 裁决 1）：第 13 个受管任务——{@code PIPELINE_EXPRESS} FIXED_DELAY tick（缺省
 * 2min，{@code job.PIPELINE_EXPRESS} 可配 1~5min，页面热切换）。高分条目（ImportanceScorer 纯规则预筛分 ≥4.0，30s 缓冲）直通
 * L0→L1→L2（复用既有服务，NEWS_PIPELINE 零改动）；与常规批并发靠 UNIQUE(news_id)+条件 UPDATE 幂等。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（十三 Job 惯例）；通道逻辑由单测直调 tick 验证（LLM 全 Mock 零外呼）。本类只做
 * tick 入口与 JobRunStats 上报。
 */
@Component
public class PipelineExpressJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(PipelineExpressJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final PipelineExpressService expressService;

    public PipelineExpressJob(PipelineExpressService expressService) {
        this.expressService = expressService;
    }

    @Override
    public String jobKey() {
        return "PIPELINE_EXPRESS";
    }

    @Override
    public String displayName() {
        return "管道快速通道";
    }

    @Override
    public String description() {
        return "高价值条目插队通道（默认 2min）：入库 30s 缓冲后按纯规则预筛分 ≥4.0 直通 L0 建行 → L1 批量归类（批 ≤10）"
                + "→ L2 当日配额重扫——入库→事件落库 ≤4.5min；低分条目不动等常规批（M16 ADR-0051 裁决 1）";
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
        PipelineExpressService.ExpressReport report = expressService.tick();
        lastProcessedCount = report.processed();
        lastRunDetail = report.detail();
        log.info("管道快速通道 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
