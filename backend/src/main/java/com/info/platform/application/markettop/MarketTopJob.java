package com.info.platform.application.markettop;

import com.info.platform.application.jobrun.JobRunStats;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.application.markettop.MarketTopService.GenerationReport;
import com.info.platform.domain.aggregation.Market;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 全市场榜单 Job（M21 T183，方案 §4.6；M29 T256 分市场——方案 §7）：第 17 个收编任务——{@code MARKET_TOP_JOB} CRON 缺省 {@code
 * 0 0 18 * * ?}（盘后 18:00，FACTOR_SNAPSHOT 17:30 完成后 30 分钟余量——独立 CRON 错开而非依赖触发，ADR-0059 裁决 7）； {@code
 * job.MARKET_TOP_JOB} 键 enabled/cron 热改。本类只做 tick 入口与 JobRunStats 上报；四阶段编排在 {@link
 * MarketTopService}。
 *
 * <p><b>每 tick 按 A_SHARE → HK → US 三市场顺序各算一轮</b>（market 内版本独立递增，三市场同日版本共存不混榜；港美股行情快照 60min 轮日频
 * 够用——美股盘中未收敛（T252 遗留 R3 未复核）榜单照常产出并在 basis 留痕数据口径）。快照日守卫在 Service 内：单市场跳过留痕不阻断其他市场。 测试 profile 种子
 * {@code enabled=false} → 调度零注册；编排逻辑由单测直调 generate 验证（LLM 全 Mock 零外呼）。
 */
@Component
public class MarketTopJob implements ManagedJob, JobRunStats {

    private static final Logger log = LoggerFactory.getLogger(MarketTopJob.class);

    /** 每轮市场序（确定性——A 股先拉通，港美随后；报告 detail 按此序拼装，同 IndustryMainlineJob 先例）。 */
    private static final List<Market> MARKETS = List.of(Market.A_SHARE, Market.HK, Market.US);

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
        return "盘后 18:00 三市场（A/港/美）四阶段榜单生成：阶段 0 行业成员覆盖率预检回填（A 股）→ 1 横截面粗筛 ~300 池 + 深析候选 40"
                + "（A 股读因子快照、港美股就地现算——F3/F5 价值维缺省权重置 0 再归一 + dimensionMissing 留痕）→ 2 LLM 深析"
                + "（仅 A 股 briefType 10，五步校验链 + 成本护栏 ≤30% 日预算触顶降级；港美股纯规则零 LLM）→ 3 Top10 合成"
                + "（final=max(总分, 0.8×总分+0.2×深析结构分)）与昨日 diff → market_top_rank/batch 追加式版本化落库"
                + "（三市场同日版本共存不混榜）；单市场快照未出守卫跳过留痕不阻断其他市场，M21 方案 §4.6 + M29 方案 §7";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：榜单日 = Asia/Shanghai 当日（守卫对账键）三市场各一轮；轮首重置统计。 */
    @Override
    public void run() {
        lastProcessedCount = 0;
        lastRunDetail = null;
        java.time.LocalDate rankDate =
                clock.instant().atZone(MarketTopService.RANK_ZONE).toLocalDate();
        int topSize = 0;
        StringBuilder details = new StringBuilder();
        for (Market market : MARKETS) {
            GenerationReport report = marketTopService.generate(rankDate, market);
            topSize += report.topSize();
            if (!details.isEmpty()) {
                details.append(" | ");
            }
            details.append(report.detail());
        }
        lastProcessedCount = topSize;
        lastRunDetail = details.toString();
        log.info("全市场榜单 tick 完成（三市场，Job 留痕摘要）: {}", lastRunDetail);
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
