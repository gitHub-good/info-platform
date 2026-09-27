package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * JobRuntimeConfigSeeder 的 SUBJECT_SYNC 种子单测（T53，技术方案增补 §4.6）：种子键存在、CRON 形态、 生产默认（enabled=true +
 * cron {@code 0 0 6 * * ?}）与测试隔离（enabled=false）由 yml 注入字段驱动——字段级注入用 {@link ReflectionTestUtils}
 * 模拟 @Value 装配值（种子的生产/测试差异只来自 yml，结构断言在此锁定）。
 */
class JobRuntimeConfigSeederTest {

    private RuntimeConfigSeed subjectSyncSeed(boolean enabled, String cron) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "subjectSyncEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "subjectSyncCron", cron);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.SUBJECT_SYNC"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.SUBJECT_SYNC 种子"));
    }

    @Test
    void seeds_productionDefaults_enabledCron0600Daily() {
        RuntimeConfigSeed seed = subjectSyncSeed(true, "0 0 6 * * ?");

        assertThat(seed.configKey()).isEqualTo("job.SUBJECT_SYNC");
        assertThat(seed.description()).contains("SubjectSyncJob");
        // 对照既有 CRON 种子（job.DAILY_RECOMMEND）形态：enabled/scheduleType/cron 三字段
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 0 6 * * ?\"");
    }

    @Test
    void seeds_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：subject.sync.enabled=false → 种子停用 → JobScheduler 零注册（隔离语义）
        RuntimeConfigSeed seed = subjectSyncSeed(false, "0 0 6 * * ?");

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"cron\":\"0 0 6 * * ?\"");
    }

    @Test
    void seeds_carriesAllEighteenJobKeys() {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        List<String> keys = seeder.seeds().stream().map(RuntimeConfigSeed::configKey).toList();

        // 纯增量守卫：既有 17 键不被增补挤占（INCREMENTAL_REEVAL 第 18 键追加在尾部，M22 T190 方案 §3.5-2）
        assertThat(keys)
                .containsExactly(
                        "job.POLICY_FETCH",
                        "job.POLICY_TENDENCY",
                        "job.ANOMALY_DETECT",
                        "job.PUSH_RETRY",
                        "job.DAILY_RECOMMEND",
                        "job.SUBJECT_SYNC",
                        "job.RETENTION_CLEANUP",
                        "job.SOURCE_POLL",
                        "job.NEWS_PIPELINE",
                        "job.INDUSTRY_HEAT_SNAPSHOT",
                        "job.INDUSTRY_DAILY_REPORT",
                        "job.SOURCE_STALE_CHECK",
                        "job.PIPELINE_EXPRESS",
                        "job.RECOMMENDATION_FEED",
                        "job.INDUSTRY_WEEKLY_REPORT",
                        "job.FACTOR_SNAPSHOT",
                        "job.MARKET_TOP_JOB",
                        "job.INCREMENTAL_REEVAL");
    }

    // ---- INCREMENTAL_REEVAL 种子（T190，M22 方案 §3.5-2：第 18 键，FIXED_DELAY 60s 短轮询）----

    private RuntimeConfigSeed incrementalReevalSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "incrementalReevalEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "incrementalReevalIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.INCREMENTAL_REEVAL"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.INCREMENTAL_REEVAL 种子"));
    }

    @Test
    void seeds_incrementalReeval_productionDefaults_enabledFixedDelay60s() {
        // 生产默认：enabled=true FIXED_DELAY 60s（可配 30s~5min 页面热切换，ADR-0061 裁决 1）
        RuntimeConfigSeed seed = incrementalReevalSeed(true, 60_000L);

        assertThat(seed.configKey()).isEqualTo("job.INCREMENTAL_REEVAL");
        assertThat(seed.description()).contains("IncrementalReevalJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":60000");
    }

    @Test
    void seeds_incrementalReeval_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：incremental.reeval.enabled=false → 种子停用 → 调度零注册（十八 Job 惯例，LLM 全 Mock 隔离）
        RuntimeConfigSeed seed = incrementalReevalSeed(false, 60_000L);

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"intervalMillis\":60000");
    }

    // ---- MARKET_TOP_JOB 种子（T183，M21 方案 §4.6：第 17 键，盘后 18:00 CRON）----

    private RuntimeConfigSeed marketTopSeed(boolean enabled, String cron) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "marketTopEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "marketTopCron", cron);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.MARKET_TOP_JOB"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.MARKET_TOP_JOB 种子"));
    }

    @Test
    void seeds_marketTop_productionDefaults_enabledCron1800Daily() {
        // 生产默认：盘后 18:00（FACTOR_SNAPSHOT 17:30 后 30 分钟余量——独立 CRON 错开而非依赖触发，ADR-0059 裁决 7）
        RuntimeConfigSeed seed = marketTopSeed(true, "0 0 18 * * ?");

        assertThat(seed.configKey()).isEqualTo("job.MARKET_TOP_JOB");
        assertThat(seed.description()).contains("MarketTopJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 0 18 * * ?\"");
    }

    @Test
    void seeds_marketTop_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：markettop.rank-job.enabled=false → 种子停用 → 调度零注册（十七 Job 惯例）
        RuntimeConfigSeed seed = marketTopSeed(false, "0 0 18 * * ?");

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"cron\":\"0 0 18 * * ?\"");
    }

    // ---- FACTOR_SNAPSHOT 种子（T170，M20 方案 §4.6：第 16 键，盘后 17:30 CRON）----

    @Test
    void seeds_factorSnapshot_cronAfterMarketClose() {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(
                seeder, "factorSnapshotEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                seeder, "factorSnapshotCron", "0 30 17 * * ?");

        RuntimeConfigSeed seed =
                seeder.seeds().stream()
                        .filter(s -> s.configKey().equals("job.FACTOR_SNAPSHOT"))
                        .findFirst()
                        .orElseThrow();

        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 30 17 * * ?\"");
        assertThat(seed.description()).contains("FactorSnapshotJob");
    }

    // ---- INDUSTRY_WEEKLY_REPORT 种子（T145，M17 / REQ 拍板四-2：第 15 键，周日晚 20:00 CRON）----

    @Test
    void seeds_industryWeeklyReport_cronSundayEvening() {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "weeklyReportEnabled", true);
        ReflectionTestUtils.setField(seeder, "weeklyReportCron", "0 0 20 * * SUN");
        RuntimeConfigSeed seed =
                seeder.seeds().stream()
                        .filter(s -> s.configKey().equals("job.INDUSTRY_WEEKLY_REPORT"))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("缺 job.INDUSTRY_WEEKLY_REPORT 种子"));

        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 0 20 * * SUN\"");
        assertThat(seed.description()).contains("周报");
    }

    // ---- RECOMMENDATION_FEED 种子（T133，M16 / ADR-0051 裁决 2：第 14 键）----

    private RuntimeConfigSeed recommendationFeedSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "recommendationFeedEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "recommendationFeedIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.RECOMMENDATION_FEED"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.RECOMMENDATION_FEED 种子"));
    }

    @Test
    void seeds_recommendationFeed_productionDefaults_enabledFixedDelay60s() {
        // 生产默认：启用 + FIXED_DELAY 60000ms（可配 30s~5min，方案 §4.9）
        RuntimeConfigSeed seed = recommendationFeedSeed(true, 60000L);

        assertThat(seed.configKey()).isEqualTo("job.RECOMMENDATION_FEED");
        assertThat(seed.description()).contains("RecommendationFeedJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":60000");
    }

    @Test
    void seeds_recommendationFeed_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：recommendation.feed.enabled=false → 种子停用 → 调度零注册（十四 Job 惯例）
        RuntimeConfigSeed seed = recommendationFeedSeed(false, 60000L);

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"intervalMillis\":60000");
    }

    // ---- PIPELINE_EXPRESS 种子（T131，M16 / ADR-0051 裁决 1：第 13 键）----

    private RuntimeConfigSeed pipelineExpressSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "pipelineExpressEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "pipelineExpressIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.PIPELINE_EXPRESS"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.PIPELINE_EXPRESS 种子"));
    }

    @Test
    void seeds_pipelineExpress_productionDefaults_enabledFixedDelay2min() {
        // 生产默认：启用 + FIXED_DELAY 120000ms（可配 60000~300000，方案 §4.9）
        RuntimeConfigSeed seed = pipelineExpressSeed(true, 120000L);

        assertThat(seed.configKey()).isEqualTo("job.PIPELINE_EXPRESS");
        assertThat(seed.description()).contains("PipelineExpressJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":120000");
    }

    @Test
    void seeds_pipelineExpress_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：pipeline.express.enabled=false → 种子停用 → 调度零注册（十三 Job 惯例）
        RuntimeConfigSeed seed = pipelineExpressSeed(false, 120000L);

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"intervalMillis\":120000");
    }

    // ---- NEWS_PIPELINE 种子（T121，M15 / ADR-0046）----

    private RuntimeConfigSeed newsPipelineSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "pipelineNewsEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "pipelineNewsIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.NEWS_PIPELINE"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.NEWS_PIPELINE 种子"));
    }

    @Test
    void seeds_newsPipeline_productionDefaults_enabledFixedDelay10min() {
        RuntimeConfigSeed seed = newsPipelineSeed(true, 600000L);

        assertThat(seed.configKey()).isEqualTo("job.NEWS_PIPELINE");
        assertThat(seed.description()).contains("NewsPipelineJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":600000");
    }

    @Test
    void seeds_newsPipeline_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：pipeline.news.enabled=false → 种子停用 → 调度零注册（九 Job 惯例）
        RuntimeConfigSeed seed = newsPipelineSeed(false, 600000L);

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"intervalMillis\":600000");
    }

    // ---- INDUSTRY_HEAT_SNAPSHOT 种子（T123，M15 / ADR-0046 裁决 4：第 10 键）----

    private RuntimeConfigSeed heatSnapshotSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "heatSnapshotEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "heatSnapshotIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.INDUSTRY_HEAT_SNAPSHOT"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.INDUSTRY_HEAT_SNAPSHOT 种子"));
    }

    @Test
    void seeds_heatSnapshot_productionDefaults_enabledFixedDelay30min() {
        RuntimeConfigSeed seed = heatSnapshotSeed(true, 1_800_000L);

        assertThat(seed.configKey()).isEqualTo("job.INDUSTRY_HEAT_SNAPSHOT");
        assertThat(seed.description()).contains("IndustryHeatSnapshotJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":1800000");
    }

    @Test
    void seeds_heatSnapshot_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：pipeline.heat-snapshot.enabled=false → 种子停用 → 调度零注册（十 Job 惯例）
        RuntimeConfigSeed seed = heatSnapshotSeed(false, 1_800_000L);

        assertThat(seed.json())
                .contains("\"enabled\":false")
                .contains("\"intervalMillis\":1800000");
    }

    // ---- INDUSTRY_DAILY_REPORT 种子（T124，M15 / ADR-0046 裁决 4：第 11 键）----

    private RuntimeConfigSeed dailyReportSeed(boolean enabled, String cron) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "dailyReportEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "dailyReportCron", cron);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.INDUSTRY_DAILY_REPORT"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.INDUSTRY_DAILY_REPORT 种子"));
    }

    @Test
    void seeds_dailyReport_productionDefaults_enabledCron0800Daily() {
        // 生产默认：每日 08:00（晨读时点；FUSED 跳过次日补，方案 §4.5）
        RuntimeConfigSeed seed = dailyReportSeed(true, "0 0 8 * * ?");

        assertThat(seed.configKey()).isEqualTo("job.INDUSTRY_DAILY_REPORT");
        assertThat(seed.description()).contains("IndustryDailyReportJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 0 8 * * ?\"");
    }

    @Test
    void seeds_dailyReport_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：pipeline.daily-report.enabled=false → 种子停用 → 调度零注册（十一 Job 惯例）
        RuntimeConfigSeed seed = dailyReportSeed(false, "0 0 8 * * ?");

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"cron\":\"0 0 8 * * ?\"");
    }

    // ---- SOURCE_STALE_CHECK 种子（T128，M15 / ADR-0046 裁决 4：第 12 键）----

    private RuntimeConfigSeed staleCheckSeed(boolean enabled, String cron) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "staleCheckEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "staleCheckCron", cron);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.SOURCE_STALE_CHECK"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.SOURCE_STALE_CHECK 种子"));
    }

    @Test
    void seeds_staleCheck_productionDefaults_enabledCron0410Daily() {
        // 生产默认：每日 04:10（避开 03:30 留痕清理与 06:00 标的池同步，ADR-0046 裁决 4）
        RuntimeConfigSeed seed = staleCheckSeed(true, "0 10 4 * * ?");

        assertThat(seed.configKey()).isEqualTo("job.SOURCE_STALE_CHECK");
        assertThat(seed.description()).contains("SourceStaleCheckJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 10 4 * * ?\"");
    }

    @Test
    void seeds_staleCheck_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：source.stale-check.enabled=false → 种子停用 → 调度零注册（十二 Job 惯例）
        RuntimeConfigSeed seed = staleCheckSeed(false, "0 10 4 * * ?");

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"cron\":\"0 10 4 * * ?\"");
    }

    // ---- SOURCE_POLL 种子（T103，M13 / ADR-0040）----

    private RuntimeConfigSeed sourcePollSeed(boolean enabled, long intervalMillis) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "sourcePollEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "sourcePollIntervalMillis", intervalMillis);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.SOURCE_POLL"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.SOURCE_POLL 种子"));
    }

    @Test
    void seeds_sourcePoll_productionDefaults_enabledFixedDelay60s() {
        RuntimeConfigSeed seed = sourcePollSeed(true, 60000L);

        assertThat(seed.configKey()).isEqualTo("job.SOURCE_POLL");
        assertThat(seed.description()).contains("SourcePollJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"FIXED_DELAY\"")
                .contains("\"intervalMillis\":60000");
    }

    @Test
    void seeds_sourcePoll_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：source.poll.enabled=false → 种子停用 → JobScheduler 零注册（八 Job 惯例）
        RuntimeConfigSeed seed = sourcePollSeed(false, 60000L);

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"intervalMillis\":60000");
    }

    // ---- RETENTION_CLEANUP 种子（T71，M10 技术方案增补 §4.1）----

    private RuntimeConfigSeed retentionSeed(boolean enabled, String cron) {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        ReflectionTestUtils.setField(seeder, "retentionCleanupEnabled", enabled);
        ReflectionTestUtils.setField(seeder, "retentionCleanupCron", cron);
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals("job.RETENTION_CLEANUP"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 job.RETENTION_CLEANUP 种子"));
    }

    @Test
    void seeds_retentionCleanup_productionDefaults_enabledCron0330Daily() {
        // 生产默认：首启即启用（首轮收敛存量），每日 03:30（避开 06:00 标的池同步与 09:00 每日推荐）
        RuntimeConfigSeed seed = retentionSeed(true, "0 30 3 * * ?");

        assertThat(seed.configKey()).isEqualTo("job.RETENTION_CLEANUP");
        assertThat(seed.description()).contains("RetentionCleanupJob");
        assertThat(seed.json())
                .contains("\"enabled\":true")
                .contains("\"scheduleType\":\"CRON\"")
                .contains("\"cron\":\"0 30 3 * * ?\"");
    }

    @Test
    void seeds_retentionCleanup_testProfileDisabled_isolatedFromScheduling() {
        // 测试 profile：retention.cleanup.enabled=false → 种子停用 → 调度零注册（对齐六 Job 惯例）
        RuntimeConfigSeed seed = retentionSeed(false, "0 30 3 * * ?");

        assertThat(seed.json()).contains("\"enabled\":false").contains("\"cron\":\"0 30 3 * * ?\"");
    }
}
