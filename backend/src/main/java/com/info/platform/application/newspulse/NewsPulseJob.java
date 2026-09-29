package com.info.platform.application.newspulse;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.newspulse.NewsPulseRepository.PulseRow;
import com.info.platform.domain.newspulse.NewsPulseWindow;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 资讯脉搏 Job（V3.2 M28）：第 19 个收编任务——{@code NEWS_PULSE} FIXED_DELAY 缺省 30min； {@code job.NEWS_PULSE} 键
 * enabled/intervalMillis 热改。每 tick 遍历六窗，仅刷新「距上次分析 ≥ 窗口时长 × 0.9」的过期窗（30m 档每 tick 必刷、24h
 * 档每日一刷，自然错峰控成本：满负荷 ≈87 次/日）。任务中心手动触发 = 全窗强制重算（同 tick 语义，非 MANUAL 留痕）。
 */
@Component
public class NewsPulseJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(NewsPulseJob.class);

    private final NewsPulseService pulseService;

    private final Clock clock;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public NewsPulseJob(NewsPulseService pulseService, Clock clock) {
        this.pulseService = pulseService;
        this.clock = clock;
    }

    @Override
    public String jobKey() {
        return "NEWS_PULSE";
    }

    @Override
    public String displayName() {
        return "资讯脉搏分析";
    }

    @Override
    public String description() {
        return "多时间窗（30分钟/1/3/6/12/24小时）资讯归纳：规则统计（L1 行业分布 + 标的回联市场归集 A股/港股/美股）恒产出"
                + " + LLM 事件归纳（大盘概览/关键事件/热点主线/情绪面，brief_type=11）失败降级纯统计；"
                + "每 30min tick 按窗口时长错峰刷新，手动刷新支持（5min 最小间隔）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.FIXED_DELAY;
    }

    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        StringBuilder detail = new StringBuilder();
        for (NewsPulseWindow window : NewsPulseWindow.values()) {
            try {
                if (!pulseService.stale(window)) {
                    continue; // 未过期跳过（错峰：本 tick 只刷到期窗）
                }
                PulseRow row = pulseService.analyze(window, false);
                lastProcessedCount++;
                detail.append(window.code())
                        .append(":news=")
                        .append(row.newsCount())
                        .append(row.degraded() ? "(降级)" : "")
                        .append(' ');
            } catch (RuntimeException e) {
                // 单窗失败不阻断其余窗（下一 tick 自然重试）
                log.warn("资讯脉搏窗口失败（跳过）: window={} {}", window.code(), e.toString());
                detail.append(window.code()).append(":error ");
            }
        }
        lastRunDetail = detail.toString().trim();
        log.info("资讯脉搏 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
