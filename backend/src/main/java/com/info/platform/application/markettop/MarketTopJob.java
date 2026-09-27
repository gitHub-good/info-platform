package com.info.platform.application.markettop;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.application.markettop.MarketTopService.GenerationReport;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 全市场榜单 Job（M21 T183，方案 §4.6）：第 17 个收编任务——{@code MARKET_TOP_JOB} CRON 缺省 {@code 0 0 18 * * ?}（盘后
 * 18:00，FACTOR_SNAPSHOT 17:30 完成后 30 分钟余量——独立 CRON 错开而非依赖触发，ADR-0059 裁决 7）； {@code
 * job.MARKET_TOP_JOB} 键 enabled/cron 热改。本类只做 tick 入口与 JobRunStats 上报；四阶段编排在 {@link
 * MarketTopService}。
 *
 * <p><b>快照日守卫</b>在 Service 内：当日快照未出 → WARN 跳过留痕（次日全量自然修复 + 任务中心手动补触发）。测试 profile 种子 {@code
 * enabled=false} → 调度零注册；编排逻辑由单测直调 generate 验证（LLM 全 Mock 零外呼）。
 */
@Component
public class MarketTopJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(MarketTopJob.class);

    private final MarketTopService marketTopService;

    private final Clock clock;

    /** 轮次统计（JobExecutor 同 jobKey CAS 守卫保证串行；轮首重置）。 */
    private int lastProcessedCount;

    private String lastRunDetail;

    public MarketTopJob(MarketTopService marketTopService, Clock clock) {
        this.marketTopService = marketTopService;
        this.clock = clock;
    }

    @Override
    public String jobKey() {
        return "MARKET_TOP_JOB";
    }

    @Override
    public String displayName() {
        return "全市场榜单";
    }

    @Override
    public String description() {
        return "盘后 18:00 四阶段榜单生成（阶段 0 行业成员覆盖率预检回填 → 1 快照粗筛 ~300 池 + 深析候选 40 → 2 LLM 深析"
                + "（briefType 10，五步校验链 + 成本护栏 ≤30% 日预算触顶降级）→ 3 Top10 合成（final=max(总分, 0.8×总分+"
                + "0.2×深析结构分)）与昨日 diff → market_top_rank/batch 追加式版本化落库；当日快照未出守卫跳过，M21 方案 §4.6）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：榜单日 = Asia/Shanghai 当日（守卫对账键）；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        GenerationReport report =
                marketTopService.generate(
                        clock.instant().atZone(MarketTopService.RANK_ZONE).toLocalDate());
        lastProcessedCount = report.topSize();
        lastRunDetail = report.detail();
        log.info("全市场榜单 tick 完成（Job 留痕摘要）: {}", lastRunDetail);
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
