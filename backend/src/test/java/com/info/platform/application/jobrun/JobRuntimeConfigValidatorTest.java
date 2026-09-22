package com.info.platform.application.jobrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JobRuntimeConfigValidator 单测（T37，方案 §4.1 键空间 + PRD「前后端双侧校验」）：cron 格式 / interval 范围 / 跨字段（间隔型与
 * cron 型互斥字段）/ userIds 格式 / 域匹配。AAA 结构。
 */
class JobRuntimeConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JobRuntimeConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new JobRuntimeConfigValidator();
    }

    private void assertValid(String json) throws Exception {
        assertThatCode(() -> validator.validate("job.POLICY_FETCH", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    private String problemsOf(String json) throws Exception {
        try {
            validator.validate("job.POLICY_FETCH", objectMapper.readTree(json));
            return "";
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
            return e.getMessage();
        }
    }

    @Test
    void supports_matchesJobDomainKeysOnly() {
        assertThat(validator.supports("job.POLICY_FETCH")).isTrue();
        assertThat(validator.supports("job.DAILY_RECOMMEND")).isTrue();
        assertThat(validator.supports("llm.global")).isFalse();
        assertThat(validator.supports("datasource.QUOTE")).isFalse();
        assertThat(validator.supports(null)).isFalse();
    }

    @Test
    void validate_fixedDelayWellFormed_passes() throws Exception {
        assertValid(
                "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":3600000}");
    }

    @Test
    void validate_cronWellFormed_passes() throws Exception {
        assertValid(
                "{\"enabled\":false,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\",\"userIds\":\"\"}");
    }

    @Test
    void validate_invalidCron_reportsFieldReason() throws Exception {
        // Arrange：非 6 段 cron（Spring CronExpression 解析失败）
        String problems =
                problemsOf("{\"enabled\":true,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * *\"}");

        // Assert：字段级原因含 cron 前缀
        assertThat(problems).contains("cron: 格式不正确");
    }

    @Test
    void validate_cronMissing_reportsRequired() throws Exception {
        String problems = problemsOf("{\"enabled\":true,\"scheduleType\":\"CRON\"}");

        assertThat(problems).contains("cron: 必填");
    }

    @Test
    void validate_fixedDelayMissingInterval_reportsRequired() throws Exception {
        String problems = problemsOf("{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\"}");

        assertThat(problems).contains("intervalMillis: 必填");
    }

    @Test
    void validate_nonPositiveInterval_reportsReason() throws Exception {
        String problems =
                problemsOf(
                        "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":0}");

        assertThat(problems).contains("intervalMillis: 须为正整数");
    }

    @Test
    void validate_fixedDelayWithCronField_reportsMismatch() throws Exception {
        String problems =
                problemsOf(
                        "{\"enabled\":true,\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":1000,"
                                + "\"cron\":\"0 0 9 * * ?\"}");

        assertThat(problems).contains("cron: 间隔型任务不支持 cron 字段");
    }

    @Test
    void validate_cronWithIntervalField_reportsMismatch() throws Exception {
        String problems =
                problemsOf(
                        "{\"enabled\":true,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\","
                                + "\"intervalMillis\":1000}");

        assertThat(problems).contains("intervalMillis: cron 型任务不支持 intervalMillis 字段");
    }

    @Test
    void validate_scheduleTypeMissingOrInvalid_reports() throws Exception {
        assertThat(problemsOf("{\"enabled\":true}")).contains("scheduleType: 必填");

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "job.X",
                                        objectMapper.readTree(
                                                "{\"enabled\":true,\"scheduleType\":\"WAT\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scheduleType");
    }

    @Test
    void validate_userIdsMalformed_reportsReason() throws Exception {
        String problems =
                problemsOf(
                        "{\"enabled\":true,\"scheduleType\":\"CRON\",\"cron\":\"0 0 9 * * ?\","
                                + "\"userIds\":\"1,a\"}");

        assertThat(problems).contains("userIds: 须为逗号分隔的用户 id");
    }

    @Test
    void validate_enabledNotBoolean_reportsReason() throws Exception {
        String problems =
                problemsOf(
                        "{\"enabled\":\"yes\",\"scheduleType\":\"FIXED_DELAY\",\"intervalMillis\":1}");

        assertThat(problems).contains("enabled: 须为布尔值");
    }
}
