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
    void seeds_carriesAllSevenJobKeys() {
        JobRuntimeConfigSeeder seeder = new JobRuntimeConfigSeeder(new ObjectMapper());
        List<String> keys = seeder.seeds().stream().map(RuntimeConfigSeed::configKey).toList();

        // 纯增量守卫：既有 6 键不被 RETENTION_CLEANUP 增补挤占（第 7 键追加在尾部）
        assertThat(keys)
                .containsExactly(
                        "job.POLICY_FETCH",
                        "job.POLICY_TENDENCY",
                        "job.ANOMALY_DETECT",
                        "job.PUSH_RETRY",
                        "job.DAILY_RECOMMEND",
                        "job.SUBJECT_SYNC",
                        "job.RETENTION_CLEANUP");
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
