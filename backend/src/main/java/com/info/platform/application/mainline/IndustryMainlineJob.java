package com.info.platform.application.mainline;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.application.mainline.IndustryMainlineService.GenerationReport;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 行业主线 Job（M27 T243，方案 §3.6 + ADR-0063 裁决 6）：第 18 个收编任务——{@code INDUSTRY_MAINLINE} CRON 缺省 {@code 0
 * 30 18 * * ?}（盘后 18:30，FACTOR_SNAPSHOT 17:30 / MARKET_TOP 18:00 之后 30min 错峰，独立 CRON 不依赖触发，沿
 * ADR-0059 裁决 7）；{@code job.INDUSTRY_MAINLINE} 键 enabled/cron 热改。任务中心手动触发 = 手动重算入口（version+1，REQ
 * 拍板二 4）。
 *
 * <p>本类只做 tick 入口与 JobRunStats 上报；三维合成 + 持续性门槛编排在 {@link IndustryMainlineService}（纯规则零 LLM）。
 */
@Component
public class IndustryMainlineJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(IndustryMainlineJob.class);

    private final IndustryMainlineService mainlineService;

    private final Clock clock;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public IndustryMainlineJob(IndustryMainlineService mainlineService, Clock clock) {
        this.mainlineService = mainlineService;
        this.clock = clock;
    }

    @Override
    public String jobKey() {
        return "INDUSTRY_MAINLINE";
    }

    @Override
    public String displayName() {
        return "行业主线计算";
    }

    @Override
    public String description() {
        return "盘后 18:30 主线+龙头+主力徽章一体计算：行情快照 × 热度双窗 × 事件密度三维百分位加权（0.40/0.35/0.25 可配）"
                + "+ 持续性硬门槛（近 5 交易日 ≥2 日价格前 1/3 或热度前 10）→ Top3~5 主线榜单 + 主线行业内龙头识别"
                + "（资讯关注度 0.50 + 价值评分 0.35 + 价格动量 0.15）+ datacenter 龙虎榜/增减持主力徽章（≤30 请求/日）；"
                + "纯规则零 LLM 版本化落库（同输入重算零漂移），手动触发 = 重算 version+1，M27 方案 §3.6";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：榜单日 = Asia/Shanghai 当日（幂等锚）；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        GenerationReport report =
                mainlineService.compute(
                        clock.instant().atZone(IndustryMainlineService.RANK_ZONE).toLocalDate(),
                        false);
        lastProcessedCount = report.topSize();
        lastRunDetail = report.detail();
        log.info("行业主线 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
