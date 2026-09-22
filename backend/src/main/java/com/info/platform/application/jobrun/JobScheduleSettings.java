package com.info.platform.application.jobrun;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单任务调度设置（{@code job.{JOB_KEY}} 文档的类型化视图，T37，方案 §4.1 键空间表）。
 *
 * <p>字段：{@code enabled(bool) / scheduleType(FIXED_DELAY|CRON) / intervalMillis(正整数，FIXED_DELAY) /
 * cron(合法表达式，CRON) / userIds(逗号分隔，仅 DAILY_RECOMMEND)}。种子由 T34 {@code JobRuntimeConfigSeeder}
 * 落库（值取当前 yml），本类只做读取侧解析。
 *
 * <p>解析健壮性：文档缺失/字段缺失/类型不符一律回落 {@link #DISABLED}（不抛出）——调度中心在种子前、 或存量值损坏时<b>宁可不调度</b>也不带病运行；写路径的合法性由
 * {@code JobRuntimeConfigValidator} 把关。
 */
public record JobScheduleSettings(
        boolean enabled, long intervalMillis, String cron, String userIds) {

    /** 兜底值：未启用（种子前 / 文档损坏 / 字段缺失时的安全缺省）。 */
    public static final JobScheduleSettings DISABLED = new JobScheduleSettings(false, 0L, null, "");

    /** {@code job.{KEY}} 键前缀（与 {@code JobRuntimeConfigSeeder} 键空间一致）。 */
    public static final String KEY_PREFIX = "job.";

    /** 从 JSON 文档解析（快照内共享节点只读）；文档 null 或 scheduleType 缺失/非法返回 {@link #DISABLED}。 */
    public static JobScheduleSettings from(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            return DISABLED;
        }
        String type = doc.path("scheduleType").asText("");
        ScheduleType scheduleType = parseType(type);
        if (scheduleType == null) {
            return DISABLED;
        }
        long interval = doc.path("intervalMillis").asLong(0L);
        String cron = doc.path("cron").isNull() ? null : doc.path("cron").asText(null);
        String userIds = doc.path("userIds").asText("");
        return new JobScheduleSettings(
                doc.path("enabled").asBoolean(false),
                interval,
                cron,
                userIds == null ? "" : userIds);
    }

    private static ScheduleType parseType(String raw) {
        for (ScheduleType type : ScheduleType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
    }
}
