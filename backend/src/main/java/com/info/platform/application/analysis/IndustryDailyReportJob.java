package com.info.platform.application.analysis;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 行业日报 Job（M15 T124，ADR-0046 裁决 4）：第 11 个收编任务——{@code INDUSTRY_DAILY_REPORT} CRON（缺省 08:00 {@code 0
 * 0 8 * * ?}，{@code job.INDUSTRY_DAILY_REPORT} 可配）。 定时语义 = 补跑窗口 [前日, 昨日] 逐日生成（FUSED 次日补 语义）；手动
 * retry 语义 = {@link #armRetry} 指定日整行重生成（JobExecutor CAS 守卫保证串行，armed 消费即清）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（十 Job 惯例）；生成逻辑由单测直调 DailyReportService 验证。
 */
@Component
public class IndustryDailyReportJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IndustryDailyReportJob.class);

    /** 手动 retry 指定日（CAS 守卫串行下原子消费；scheduled tick 抢先消费 = 同效重生成，可接受）。 */
    private final AtomicReference<LocalDate> armedRetryDate = new AtomicReference<>();

    private int lastProcessedCount;

    private String lastRunDetail;

    private final DailyReportService reportService;

    public IndustryDailyReportJob(DailyReportService reportService) {
        this.reportService = reportService;
    }

    /** 手动重试指定日（retry 端点校验 FAILED 后武装；triggerNow 409 时由调用方 disarm）。 */
    public void armRetry(LocalDate reportDate) {
        armedRetryDate.set(reportDate);
    }

    /** 解除武装（triggerNow 未受理时回滚，避免污染下一轮定时语义）。 */
    public void disarmRetry() {
        armedRetryDate.set(null);
    }

    @Override
    public String jobKey() {
        return "INDUSTRY_DAILY_REPORT";
    }

    @Override
    public String displayName() {
        return "行业日报";
    }

    @Override
    public String description() {
        return "前一日行业统计注入模板单次 LLM 生成日报（数字全部来自统计 SQL，AI 只写叙述）→ industry_daily_report 落库"
                + "（幂等/FAILED 重试；FUSED 跳过次日补，DEGRADED 保留——M15 方案 §4.5）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：armed 日优先（手动 retry），否则定时补跑窗口。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        LocalDate armed = armedRetryDate.getAndSet(null);
        if (armed != null) {
            // 手动 retry 指定日（端点已校验 FAILED；armed 消费即清，下一轮恢复定时语义）
            DailyReportService.GenerationOutcome outcome =
                    reportService.generateFor(armed.toString());
            lastProcessedCount = outcome.skipped() ? 0 : 1;
            lastRunDetail =
                    "retry date="
                            + armed
                            + ";status="
                            + outcome.status()
                            + (outcome.reason() == null ? "" : ";reason=" + outcome.reason());
            log.info("行业日报手动重试完成（Job 留痕摘要）: {}", lastRunDetail);
            return;
        }
        DailyReportService.WindowReport report = reportService.runScheduledWindow();
        lastProcessedCount = report.generated();
        lastRunDetail = report.detail();
        log.info("行业日报定时窗口完成（Job 留痕摘要）: {}", lastRunDetail);
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
