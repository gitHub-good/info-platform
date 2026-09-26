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
 * 行业周报 Job（M17 T145，REQ 拍板四-2）：第 15 个收编任务——{@code INDUSTRY_WEEKLY_REPORT} CRON（缺省周日晚 20:00
 * {@code 0 0 20 * * SUN}，{@code job.INDUSTRY_WEEKLY_REPORT} 可配）。定时语义 = 生成当周周报（本周一锚点 ~ 生成时刻窗口）；手动
 * retry 语义 = {@link #armRetry} 指定周整行重生成（JobExecutor CAS 守卫保证串行，armed 消费即清）。
 *
 * <p>测试 profile 种子 {@code enabled=false} → 调度零注册（十五 Job 惯例）；生成逻辑由单测直调 WeeklyReportService 验证。
 */
@Component
public class IndustryWeeklyReportJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IndustryWeeklyReportJob.class);

    /** 手动 retry 指定周（CAS 守卫串行下原子消费；scheduled tick 抢先消费 = 同效重生成，可接受）。 */
    private final AtomicReference<LocalDate> armedRetryWeek = new AtomicReference<>();

    private int lastProcessedCount;

    private String lastRunDetail;

    private final WeeklyReportService reportService;

    public IndustryWeeklyReportJob(WeeklyReportService reportService) {
        this.reportService = reportService;
    }

    /** 手动重试指定周（retry 端点校验 FAILED 后武装；triggerNow 409 时由调用方 disarm）。 */
    public void armRetry(LocalDate weekStart) {
        armedRetryWeek.set(weekStart);
    }

    /** 解除武装（triggerNow 未受理时回滚，避免污染下一轮定时语义）。 */
    public void disarmRetry() {
        armedRetryWeek.set(null);
    }

    @Override
    public String jobKey() {
        return "INDUSTRY_WEEKLY_REPORT";
    }

    @Override
    public String displayName() {
        return "行业周报";
    }

    @Override
    public String description() {
        return "周日晚 20:00 生成当周行业周报（周窗聚合：热度周环比现算 + 事件主键归并跨日去重 + 政策动向 → 五区块含走向判断 v1"
                + "——置信度 trend-v1 规则层锁定，AI 仅语言组织）→ industry_weekly_report 落库"
                + "（幂等/FAILED 重试；FUSED 跳过下周一覆盖，DEGRADED 保留——M17 REQ 拍板四）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：armed 周优先（手动 retry），否则定时当周。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        LocalDate armed = armedRetryWeek.getAndSet(null);
        if (armed != null) {
            WeeklyReportService.GenerationOutcome outcome = reportService.generateFor(armed.toString());
            lastProcessedCount = outcome.skipped() ? 0 : 1;
            lastRunDetail =
                    "retry weekStart="
                            + armed
                            + ";status="
                            + outcome.status()
                            + (outcome.reason() == null ? "" : ";reason=" + outcome.reason());
            log.info("行业周报手动重试完成（Job 留痕摘要）: {}", lastRunDetail);
            return;
        }
        WeeklyReportService.WindowReport report = reportService.runScheduledWindow();
        lastProcessedCount = report.generated();
        lastRunDetail = report.detail();
        log.info("行业周报定时窗口完成（Job 留痕摘要）: {}", lastRunDetail);
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
