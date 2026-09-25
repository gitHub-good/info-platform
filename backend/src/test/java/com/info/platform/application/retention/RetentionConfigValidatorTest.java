package com.info.platform.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * RetentionConfigValidator 单测（T72，方案 §4.2 保存侧防御 / §6 配置与接口组）：四字段非法矩阵（缺失 / 非整型 / 低于下限
 * → 2001 字段级 msg，多字段 "; " 连接）+ 合法值通过（恰在下限）+ 未知多余字段不拒绝（读侧忽略，job 域惯例）。
 */
class RetentionConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RetentionConfigValidator validator = new RetentionConfigValidator();

    private void validate(String json) {
        try {
            validator.validate("retention.global", objectMapper.readTree(json));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void supports_onlyRetentionGlobalKey() {
        assertThat(validator.supports("retention.global")).isTrue();
        assertThat(validator.supports("retention")).isFalse();
        assertThat(validator.supports("job.RETENTION_CLEANUP")).isFalse();
        assertThat(validator.supports(null)).isFalse();
    }

    @Test
    void validate_legalDocument_passes() {
        // 四字段均为合法整型（默认窗口）
        assertThatCode(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_valuesAtLowerBound_pass() {
        // 恰等于下限 7/2/35/35 合法（下限含边界）
        assertThatCode(() -> validate("{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":2,"
                        + "\"llmCallLogDays\":35,\"readingEventDays\":35}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_missingField_rejectedWithFieldLevelMessage() {
        // 缺 llmCallLogDays → 2001 + 字段级原因
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"readingEventDays\":90}"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("llmCallLogDays")
                .hasMessageContaining("必填");
    }

    @Test
    void validate_belowLowerBound_rejectedWithMinimumInMessage() {
        // 误配双重防御·保存侧：0/负数/低于下限全拒绝（「设 0 清空」第一道拦截）
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":6,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("jobExecutionLogDays")
                .hasMessageContaining("7");
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":1,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .hasMessageContaining("dataSourceEventDays")
                .hasMessageContaining("2");
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":34,\"readingEventDays\":90}"))
                .hasMessageContaining("llmCallLogDays")
                .hasMessageContaining("35");
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":0}"))
                .hasMessageContaining("readingEventDays")
                .hasMessageContaining("35");
    }

    @Test
    void validate_nonInteger_rejected() {
        // 非整型：文本 / 小数 / 布尔
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":\"30\",\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .hasMessageContaining("jobExecutionLogDays");
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":30.5,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .hasMessageContaining("jobExecutionLogDays");
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":true,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .hasMessageContaining("jobExecutionLogDays");
    }

    @Test
    void validate_multipleProblems_joinedWithSemicolon() {
        // 多字段同时非法 → 一条 2001，字段级原因 "; " 连接（对齐 JobRuntimeConfigValidator 惯例）
        assertThatThrownBy(() -> validate("{\"jobExecutionLogDays\":0,\"dataSourceEventDays\":0,"
                        + "\"llmCallLogDays\":0,\"readingEventDays\":0}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("; ")
                .hasMessageContaining("jobExecutionLogDays")
                .hasMessageContaining("dataSourceEventDays")
                .hasMessageContaining("llmCallLogDays")
                .hasMessageContaining("readingEventDays");
    }

    @Test
    void validate_unknownExtraFields_notRejected() {
        // 未知多余字段不拒绝（读侧忽略，job 域惯例——向前兼容）
        assertThatCode(() -> validate("{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"futureField\":\"x\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_noUpperBound_largeValuePasses() {
        // 无上限：改大=少删/不删，方向安全（REQ 裁决）
        assertThatCode(() -> validate("{\"jobExecutionLogDays\":3650,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}"))
                .doesNotThrowAnyException();
    }
}
