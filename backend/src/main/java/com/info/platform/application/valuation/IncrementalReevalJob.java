package com.info.platform.application.valuation;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 增量重评 Job（M22 T190，ADR-0061 裁决 1）：第 18 个受管任务——{@code INCREMENTAL_REEVAL} FIXED_DELAY tick（缺省 60s，
 * {@code job.INCREMENTAL_REEVAL} 可配
 * 30s~5min，页面热切换）。与推荐消费<b>完全解耦</b>（停推荐不动增量、反之亦然）；重启免疫（扫描无内存态，消费进度全在 {@code incremental_reeval_log}
 * 留痕表）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（十八 Job 惯例）；链路逻辑由单测直调 tick 验证（LLM 全 Mock 零外呼）。 本类只做
 * tick 入口与 JobRunStats 上报。
 */
@Component
public class IncrementalReevalJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IncrementalReevalJob.class);

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行，普通字段即可；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    private final IncrementalReevalService reevalService;

    public IncrementalReevalJob(IncrementalReevalService reevalService) {
        this.reevalService = reevalService;
    }

    @Override
    public String jobKey() {
        return "INCREMENTAL_REEVAL";
    }

    @Override
    public String displayName() {
        return "增量重评";
    }

    @Override
    public String description() {
        return "事件驱动增量重评 tick（默认 60s）：扫 24h 内未消费的 ≥HIGH 事件（20s 落库缓冲 + LEFT JOIN 留痕表判重）→ 受影响标的集"
                + "（事件直接关联 ∪ 行业成员投影，与 F2 同源）→ 当日因子快照行局部重算（同投影同纯函数零漂移，increment_at 留痕）→ "
                + "挤入挤出迟滞判定（阈值/间隔见 incremental.reeval 键热改）→ 榜单同日 version+1 联动（EVENT 归因留痕）；"
                + "17:30/18:00 重 Job 运行时让路下轮重试，异常 FAILED 次日全量自愈——M22 方案 §4.3.1 / ADR-0061";
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
        IncrementalReevalService.ReevalReport report = reevalService.tick();
        lastProcessedCount = report.processed();
        lastRunDetail = report.detail();
        log.info("增量重评 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
