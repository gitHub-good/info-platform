package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务域种子（{@code job.*} 8 键，T34；T53 增 {@code job.SUBJECT_SYNC}，T71 增 {@code job.RETENTION_CLEANUP}，
 * M13 T103 增 {@code job.SOURCE_POLL}，ADR-0040；M16 T131 增第 13 键 {@code
 * job.PIPELINE_EXPRESS}，ADR-0051 裁决 1）。
 *
 * <p>键值对照方案 §4.1「任务键与既有 Job 对照表」：jobKey/jobName 对齐既有 yml 开关与 {@code job_execution_log.job_name}。
 * 种子值取当前 yml（测试 profile 各开关为 false → 种子 enabled=false → T37 调度中心零注册，隔离语义等价平移）。消费与 校验器随 T37 落地。
 */
@Component
public class JobRuntimeConfigSeeder implements RuntimeConfigSeeder {

    /** scheduleType 取值（对齐方案 §4.1 键空间表）。 */
    private static final String FIXED_DELAY = "FIXED_DELAY";

    private static final String CRON = "CRON";

    private final ObjectMapper objectMapper;

    /** 资讯源轮询开关/tick 间隔（M13：SOURCE_POLL 聚合 Job，默认 60s，可配 15s~300s）。 */
    @Value("${source.poll.enabled:true}")
    private boolean sourcePollEnabled;

    @Value("${source.poll.interval-millis:60000}")
    private long sourcePollIntervalMillis;

    @Value("${anomaly.detect.enabled:true}")
    private boolean anomalyDetectEnabled;

    @Value("${anomaly.detect-interval-millis:10000}")
    private long anomalyDetectIntervalMillis;

    @Value("${push.retry.enabled:true}")
    private boolean pushRetryEnabled;

    @Value("${push.retry.interval-millis:30000}")
    private long pushRetryIntervalMillis;

    @Value("${recommendation.schedule.enabled:false}")
    private boolean dailyRecommendEnabled;

    @Value("${recommendation.schedule.cron:0 0 9 * * ?}")
    private String dailyRecommendCron;

    @Value("${recommendation.schedule.user-ids:}")
    private String dailyRecommendUserIds;

    @Value("${subject.sync.enabled:true}")
    private boolean subjectSyncEnabled;

    @Value("${subject.sync.cron:0 0 6 * * ?}")
    private String subjectSyncCron;

    @Value("${retention.cleanup.enabled:true}")
    private boolean retentionCleanupEnabled;

    @Value("${retention.cleanup.cron:0 30 3 * * ?}")
    private String retentionCleanupCron;

    /** AI 归类管道开关/批窗口（M15 T121：NEWS_PIPELINE 聚合 Job，默认 10min，可配 5~15min，ADR-0046 裁决 4）。 */
    @Value("${pipeline.news.enabled:true}")
    private boolean pipelineNewsEnabled;

    @Value("${pipeline.news.interval-millis:600000}")
    private long pipelineNewsIntervalMillis;

    /** 行业热度快照开关/tick 间隔（M15 T123：INDUSTRY_HEAT_SNAPSHOT，默认 30min，可配 10~60min，零 LLM）。 */
    @Value("${pipeline.heat-snapshot.enabled:true}")
    private boolean heatSnapshotEnabled;

    @Value("${pipeline.heat-snapshot.interval-millis:1800000}")
    private long heatSnapshotIntervalMillis;

    /** 行业日报开关/CRON（M15 T124：INDUSTRY_DAILY_REPORT，默认每日 08:00 可配，ADR-0046 裁决 4）。 */
    @Value("${pipeline.daily-report.enabled:true}")
    private boolean dailyReportEnabled;

    @Value("${pipeline.daily-report.cron:0 0 8 * * ?}")
    private String dailyReportCron;

    /** 疑似停更检查开关/CRON（M15 T128：SOURCE_STALE_CHECK，默认每日 04:10，ADR-0046 裁决 4）。 */
    @Value("${source.stale-check.enabled:true}")
    private boolean staleCheckEnabled;

    @Value("${source.stale-check.cron:0 10 4 * * ?}")
    private String staleCheckCron;

    /** 高价值快速通道开关/tick 间隔（M16 T131：PIPELINE_EXPRESS 第 13 键，默认 2min 可配 1~5min，ADR-0051 裁决 1）。 */
    @Value("${pipeline.express.enabled:true}")
    private boolean pipelineExpressEnabled;

    @Value("${pipeline.express.interval-millis:120000}")
    private long pipelineExpressIntervalMillis;

    /** 推荐事件消费开关/tick 间隔（M16 T133：RECOMMENDATION_FEED 第 14 键，默认 60s 可配 30s~5min，ADR-0051 裁决 2）。 */
    @Value("${recommendation.feed.enabled:true}")
    private boolean recommendationFeedEnabled;

    @Value("${recommendation.feed.interval-millis:60000}")
    private long recommendationFeedIntervalMillis;

    /** 行业周报开关/CRON（M17 T145：INDUSTRY_WEEKLY_REPORT 第 15 键，缺省周日晚 20:00，REQ 拍板四-2）。 */
    @Value("${pipeline.weekly-report.enabled:true}")
    private boolean weeklyReportEnabled;

    @Value("${pipeline.weekly-report.cron:0 0 20 * * SUN}")
    private String weeklyReportCron;

    /** 因子快照开关/CRON（M20 T170：FACTOR_SNAPSHOT 第 16 键，缺省盘后 17:30 日频，方案 §4.6）。 */
    @Value("${valuation.factor-snapshot.enabled:true}")
    private boolean factorSnapshotEnabled;

    @Value("${valuation.factor-snapshot.cron:0 30 17 * * ?}")
    private String factorSnapshotCron;

    /**
     * 全市场榜单开关/CRON（M21 T183：MARKET_TOP_JOB 第 17 键，缺省盘后 18:00 日频，方案 §4.6——FACTOR_SNAPSHOT 后 30
     * 分钟余量）。
     */
    @Value("${markettop.rank-job.enabled:true}")
    private boolean marketTopEnabled;

    @Value("${markettop.rank-job.cron:0 0 18 * * ?}")
    private String marketTopCron;

    /** 增量重评开关/tick 间隔（M22 T190：INCREMENTAL_REEVAL 第 18 键，默认 60s 可配 30s~5min，ADR-0061 裁决 1）。 */
    @Value("${incremental.reeval.enabled:true}")
    private boolean incrementalReevalEnabled;

    @Value("${incremental.reeval.interval-millis:60000}")
    private long incrementalReevalIntervalMillis;

    /** 行业行情快照开关/tick 间隔（M27 T242：INDUSTRY_MARKET_SNAPSHOT，默认 30min 可配 15~60min，ADR-0063 裁决 3/6）。 */
    @Value("${industry.market-snapshot.enabled:true}")
    private boolean industryMarketSnapshotEnabled;

    @Value("${industry.market-snapshot.interval-millis:1800000}")
    private long industryMarketSnapshotIntervalMillis;

    /**
     * 行业主线开关/CRON（M27 T243：INDUSTRY_MAINLINE，盘后 18:30 日频——MARKET_TOP 18:00 后 30min 错峰，ADR-0063 裁决
     * 6）。
     */
    @Value("${industry.mainline-job.enabled:true}")
    private boolean industryMainlineEnabled;

    @Value("${industry.mainline-job.cron:0 30 18 * * ?}")
    private String industryMainlineCron;

    /** 资讯脉搏开关/tick 间隔（V3.2 M28：NEWS_PULSE，默认 30min，可配 5~60min）。 */
    @Value("${news.pulse-job.enabled:true}")
    private boolean newsPulseEnabled;

    @Value("${news.pulse-job.interval-millis:1800000}")
    private long newsPulseIntervalMillis;

    /**
     * 港美股行情快照开关/tick 间隔（M29 T252：HKUS_MARKET_SNAPSHOT 第 20 键，默认 60min 可配 30~120min，ADR-0064 裁决 4）。
     */
    @Value("${hkus.market-snapshot.enabled:true}")
    private boolean hkusMarketSnapshotEnabled;

    @Value("${hkus.market-snapshot.interval-millis:3600000}")
    private long hkusMarketSnapshotIntervalMillis;

    public JobRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        seeds.add(
                fixedDelay(
                        "ANOMALY_DETECT",
                        "异动检测任务调度（AnomalyDetectionJob，活跃标的行情阈值轮询）",
                        anomalyDetectEnabled,
                        anomalyDetectIntervalMillis));
        seeds.add(
                fixedDelay(
                        "PUSH_RETRY",
                        "推送补推任务调度（PushRetryJob，未消费异动与失败推送补拉）",
                        pushRetryEnabled,
                        pushRetryIntervalMillis));
        Map<String, Object> daily = new LinkedHashMap<>();
        daily.put("enabled", dailyRecommendEnabled);
        daily.put("scheduleType", CRON);
        daily.put("cron", dailyRecommendCron);
        daily.put("userIds", dailyRecommendUserIds == null ? "" : dailyRecommendUserIds);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.DAILY_RECOMMEND", write(daily), "每日推荐盘前预热调度（DailyRecommendationJob）"));
        Map<String, Object> subjectSync = new LinkedHashMap<>();
        subjectSync.put("enabled", subjectSyncEnabled);
        subjectSync.put("scheduleType", CRON);
        subjectSync.put("cron", subjectSyncCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.SUBJECT_SYNC",
                        write(subjectSync),
                        "标的池同步调度（SubjectSyncJob，每日全量拉取 A 股/港股/指数保鲜标的池，M7 技术方案增补 §4.6）"));
        Map<String, Object> retentionCleanup = new LinkedHashMap<>();
        retentionCleanup.put("enabled", retentionCleanupEnabled);
        retentionCleanup.put("scheduleType", CRON);
        retentionCleanup.put("cron", retentionCleanupCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.RETENTION_CLEANUP",
                        write(retentionCleanup),
                        "留痕清理调度（RetentionCleanupJob，每日 03:30 清理四张留痕表过期行，窗口见 retention.global，"
                                + "M10 技术方案增补 §4.1）"));
        seeds.add(
                fixedDelay(
                        "SOURCE_POLL",
                        "资讯源轮询调度（SourcePollJob，分钟级聚合轮询全部启用资讯源：错峰/退避/断流补抓，M13 ADR-0040）",
                        sourcePollEnabled,
                        sourcePollIntervalMillis));
        seeds.add(
                fixedDelay(
                        "NEWS_PIPELINE",
                        "AI 归类管道调度（NewsPipelineJob，批窗口 10min：L0 规则预筛 → L1 批量归类 → L2 事件提取[T122]，"
                                + "段间独立容错，M15 ADR-0046）",
                        pipelineNewsEnabled,
                        pipelineNewsIntervalMillis));
        seeds.add(
                fixedDelay(
                        "INDUSTRY_HEAT_SNAPSHOT",
                        "行业热度快照调度（IndustryHeatSnapshotJob，双窗 24h/7d 现算 31 申万行业热度 → 62 行 UPSERT，"
                                + "零 LLM 护栏不停，M15 方案 §4.5）",
                        heatSnapshotEnabled,
                        heatSnapshotIntervalMillis));
        Map<String, Object> dailyReport = new LinkedHashMap<>();
        dailyReport.put("enabled", dailyReportEnabled);
        dailyReport.put("scheduleType", CRON);
        dailyReport.put("cron", dailyReportCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.INDUSTRY_DAILY_REPORT",
                        write(dailyReport),
                        "行业日报调度（IndustryDailyReportJob，每日 08:00 统计注入模板单次 LLM 生成前一日日报——"
                                + "数字全部来自统计 SQL，FUSED 跳过次日补，M15 方案 §4.5）"));
        Map<String, Object> staleCheck = new LinkedHashMap<>();
        staleCheck.put("enabled", staleCheckEnabled);
        staleCheck.put("scheduleType", CRON);
        staleCheck.put("cron", staleCheckCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.SOURCE_STALE_CHECK",
                        write(staleCheck),
                        "疑似停更检查调度（SourceStaleCheckJob，每日 04:10 各启用源滚动窗净入库判定 → config.staleSince"
                                + " 标记/恢复解除，不自动停用，M15 方案 §4.7 / REQ AMB-01）"));
        seeds.add(
                fixedDelay(
                        "PIPELINE_EXPRESS",
                        "管道快速通道调度（PipelineExpressJob，tick 2min：高分条目纯规则预筛分 ≥4.0 直通 L0→L1→L2 复用既有服务，"
                                + "入库→事件落库 ≤4.5min；低分条目不动等常规批，M16 ADR-0051 裁决 1）",
                        pipelineExpressEnabled,
                        pipelineExpressIntervalMillis));
        seeds.add(
                fixedDelay(
                        "RECOMMENDATION_FEED",
                        "推荐事件消费调度（RecommendationFeedJob，tick 60s：扫 24h 内未消费事件 → 每用户三级关联 → 卡片生成"
                                + "→ 推送闸门（降频/排序/日上限/SILENT 静默留痕），M16 ADR-0051 裁决 2）",
                        recommendationFeedEnabled,
                        recommendationFeedIntervalMillis));
        Map<String, Object> weeklyReport = new LinkedHashMap<>();
        weeklyReport.put("enabled", weeklyReportEnabled);
        weeklyReport.put("scheduleType", CRON);
        weeklyReport.put("cron", weeklyReportCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.INDUSTRY_WEEKLY_REPORT",
                        write(weeklyReport),
                        "行业周报调度（IndustryWeeklyReportJob，周日晚 20:00 生成当周周报：周窗聚合热度环比/事件主键归并/政策动向"
                                + "→ 五区块含走向判断 v1（置信度 trend-v1 规则层锁定）——FUSED 跳过下周一覆盖，M17 REQ 拍板四）"));
        Map<String, Object> factorSnapshot = new LinkedHashMap<>();
        factorSnapshot.put("enabled", factorSnapshotEnabled);
        factorSnapshot.put("scheduleType", CRON);
        factorSnapshot.put("cron", factorSnapshotCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.FACTOR_SNAPSHOT",
                        write(factorSnapshot),
                        "因子快照调度（FactorSnapshotJob，盘后 17:30 全市场五因子计算→subject_factor_snapshot 5221 行/日"
                                + "幂等 UPSERT + 腾讯批量行情日快照（失败降级不阻塞四维），权重/窗口热改见 score.weight 键，"
                                + "M20 方案 §4.6）"));
        Map<String, Object> marketTop = new LinkedHashMap<>();
        marketTop.put("enabled", marketTopEnabled);
        marketTop.put("scheduleType", CRON);
        marketTop.put("cron", marketTopCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.MARKET_TOP_JOB",
                        write(marketTop),
                        "全市场榜单调度（MarketTopJob，盘后 18:00 四阶段生成：行业成员回填预检→快照粗筛 300 池→LLM 深析 40"
                                + "（briefType 10，五步校验链+scene-10 成本护栏）→Top10 合成与昨日 diff→两表版本化落库；"
                                + "当日快照未出守卫跳过；漏斗参数热改见 market.top 键，M21 方案 §4.6）"));
        seeds.add(
                fixedDelay(
                        "INCREMENTAL_REEVAL",
                        "增量重评调度（IncrementalReevalJob，tick 60s：扫未消费 ≥HIGH 事件 → 受影响标的当日快照行局部重算"
                                + "（与全量同源零漂移，increment_at 留痕）→ 挤入挤出迟滞判定 → 榜单 version+1 联动与归因留痕；"
                                + "重 Job 运行让路/联动间隔防抖，阈值热改见 incremental.reeval 键，M22 ADR-0061）",
                        incrementalReevalEnabled,
                        incrementalReevalIntervalMillis));
        seeds.add(
                fixedDelay(
                        "INDUSTRY_MARKET_SNAPSHOT",
                        "行业行情快照调度（IndustryMarketSnapshotJob，盘中每 30min 双通道采集：主东财 push2 板块 → "
                                + "swPrimaryOf 聚合申万 31，失败当轮切腾讯板块排行直出 → industry_market_snapshot 当日行"
                                + "幂等 UPSERT ~117 行/日；双通道全败沿用旧快照（页面 stale 标注），连续 5 轮失败通知中心"
                                + "告警，M27 方案 §4.2）",
                        industryMarketSnapshotEnabled,
                        industryMarketSnapshotIntervalMillis));
        Map<String, Object> industryMainline = new LinkedHashMap<>();
        industryMainline.put("enabled", industryMainlineEnabled);
        industryMainline.put("scheduleType", CRON);
        industryMainline.put("cron", industryMainlineCron);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.INDUSTRY_MAINLINE",
                        write(industryMainline),
                        "行业主线调度（IndustryMainlineJob，盘后 18:30 主线+龙头+主力徽章一体计算：行情快照×热度×事件"
                                + "三维百分位加权（0.40/0.35/0.25 可配）+ 持续性硬门槛 → Top3~5 主线榜单；主线行业内龙头"
                                + "三维识别（资讯关注度 0.50+价值 0.35+价格动量 0.15）+ datacenter 龙虎榜/增减持主力徽章"
                                + "（≤30 请求/日），纯规则零 LLM 版本化落库；任务中心手动触发=重算 version+1；参数热改见"
                                + " industry.mainline/industry.leader 键，M27 方案 §3.6）"));
        seeds.add(
                fixedDelay(
                        "NEWS_PULSE",
                        "资讯脉搏调度（NewsPulseJob，每 30min tick 遍历六窗（30m/1h/3h/6h/12h/24h）按窗口时长错峰刷新："
                                + "规则统计（L1 行业分布+标的回联市场归集 A股/港股/美股）恒产出 + LLM 事件归纳（大盘概览/"
                                + "关键事件/热点主线/情绪面，brief_type=11）失败降级纯统计；手动刷新 5min 最小间隔，"
                                + "V3.2 M28）",
                        newsPulseEnabled,
                        newsPulseIntervalMillis));
        seeds.add(
                fixedDelay(
                        "HKUS_MARKET_SNAPSHOT",
                        "港美股行情快照调度（HKUSMarketSnapshotJob，每 60min 可配 30~120 一职三责轮：腾讯主/新浪备批量行情"
                                + "（50/请求 500ms 间隔，整轮失败轮级切备链 source 留痕）→ market_daily_snapshot 当日幂等 "
                                + "UPSERT（含 market_cap/currency 原币）+ 个股×行业就地聚合（市值加权，覆盖<80% 回退等权 "
                                + "agg_method 留痕）+ 美股代表集市值收敛（<20 亿 USD status=0 留池，每日首轮宽取维护升降级），"
                                + "M29 ADR-0064 裁决 1/3/4）",
                        hkusMarketSnapshotEnabled,
                        hkusMarketSnapshotIntervalMillis));
        return seeds;
    }

    private RuntimeConfigSeed fixedDelay(
            String jobKey, String description, boolean enabled, long intervalMillis) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("enabled", enabled);
        doc.put("scheduleType", FIXED_DELAY);
        doc.put("intervalMillis", intervalMillis);
        return new RuntimeConfigSeed("job." + jobKey, write(doc), description);
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
