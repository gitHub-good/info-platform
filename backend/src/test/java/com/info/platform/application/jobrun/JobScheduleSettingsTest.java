package com.info.platform.application.jobrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** JobScheduleSettings 解析单测（T37）：类型化视图的健壮回落（文档缺失/损坏/字段缺失 → DISABLED，宁可不调度不带病运行）。 AAA 结构。 */
class JobScheduleSettingsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JobScheduleSettings parse(String json) {
        try {
            return JobScheduleSettings.from(objectMapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void from_wellFormedFixedDelay_parsesAllFields() {
        JobScheduleSettings settings =
                parse(
                        "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":30000,\"userIds\":\"\"}");

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.intervalMillis()).isEqualTo(30000L);
        assertThat(settings.cron()).isNull();
        assertThat(settings.userIds()).isEmpty();
    }

    @Test
    void from_wellFormedCron_parsesAllFields() {
        JobScheduleSettings settings =
                parse(
                        "{\"enabled\":true,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\",\"userIds\":\"1,2\"}");

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.cron()).isEqualTo("0 0 9 * * ?");
        assertThat(settings.userIds()).isEqualTo("1,2");
    }

    @Test
    void from_nullOrNonObject_returnsDisabled() {
        assertThat(JobScheduleSettings.from(null)).isSameAs(JobScheduleSettings.DISABLED);
        assertThat(JobScheduleSettings.from(objectMapper.nullNode()))
                .isSameAs(JobScheduleSettings.DISABLED);
        assertThat(parse("[1,2]")).isSameAs(JobScheduleSettings.DISABLED);
        assertThat(parse("\"just a string\"")).isSameAs(JobScheduleSettings.DISABLED);
    }

    @Test
    void from_missingOrInvalidScheduleType_returnsDisabled() {
        assertThat(parse("{}")).isSameAs(JobScheduleSettings.DISABLED);
        assertThat(parse("{\"enabled\":true}")).isSameAs(JobScheduleSettings.DISABLED);
        assertThat(parse("{\"enabled\":true,\"scheduleType\":\"WAT\",\"intervalMillis\":1}"))
                .isSameAs(JobScheduleSettings.DISABLED);
    }

    @Test
    void from_missingFields_fallsBackToDefaults() {
        // scheduleType 合法但其余字段缺失：不抛出，回落缺省（enabled=false / interval=0 / userIds 空）
        JobScheduleSettings settings = parse("{\"scheduleType\":\"FIXED_DELAY\"}");

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.intervalMillis()).isZero();
        assertThat(settings.userIds()).isEmpty();
        assertThat(parse("{\"scheduleType\":\"FIXED_DELAY\",\"cron\":null}").cron()).isNull();
    }
}
