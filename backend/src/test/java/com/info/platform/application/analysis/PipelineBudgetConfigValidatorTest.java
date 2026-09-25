package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.push.PipelineFusedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * PipelineBudgetConfigValidator 单测（T125，方案 §4.8 保存侧防御）：pipeline.budget 字段级校验——正整数预算/比例 0~1 开区间/fuse
 * &gt; degrade 跨字段/校准值非负；合法文档通过。另覆盖 PipelineFusedEvent 构造守卫。AAA 结构。
 */
class PipelineBudgetConfigValidatorTest {

    private final PipelineBudgetConfigValidator validator = new PipelineBudgetConfigValidator();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private void validate(String json) throws Exception {
        validator.validate("pipeline.budget", MAPPER.readTree(json));
    }

    @Test
    void supports_onlyBudgetKey() {
        org.assertj.core.api.Assertions.assertThat(validator.supports("pipeline.budget")).isTrue();
        org.assertj.core.api.Assertions.assertThat(validator.supports("pipeline.heat")).isFalse();
    }

    @Test
    void validate_seedDefaults_pass() throws Exception {
        assertThatCode(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":2000000,\"degradeRatio\":0.6,"
                                                + "\"fuseRatio\":0.9,\"calibratedPerItemMicros\":1100,"
                                                + "\"costBasis\":\"cost-v1:initial\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_missingOrNonPositiveBudget_fieldLevelMessage() {
        assertThatThrownBy(() -> validate("{\"degradeRatio\":0.6,\"fuseRatio\":0.9}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dailyBudgetMicros: 必填");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":0,\"degradeRatio\":0.6,\"fuseRatio\":0.9}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dailyBudgetMicros: 须为正整数");
    }

    @Test
    void validate_ratioBounds_fieldLevel() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":100,\"degradeRatio\":1.0,\"fuseRatio\":0.9}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("degradeRatio: 须为 0~1 开区间比例");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":100,\"degradeRatio\":0.6,\"fuseRatio\":\"x\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fuseRatio: 须为 0~1 开区间比例");
    }

    @Test
    void validate_fuseMustExceedDegrade_crossFieldRule() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":100,\"degradeRatio\":0.9,\"fuseRatio\":0.9}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fuseRatio: 须大于 degradeRatio");
    }

    @Test
    void validate_calibratedNegative_rejected() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"dailyBudgetMicros\":100,\"degradeRatio\":0.6,"
                                                + "\"fuseRatio\":0.9,\"calibratedPerItemMicros\":-1}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("calibratedPerItemMicros: 须为非负整数");
    }

    @Test
    void fusedEvent_constructorGuards() {
        assertThatCode(() -> new PipelineFusedEvent(1L, 2_000_000L, Instant.EPOCH))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new PipelineFusedEvent(1L, 0L, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetMicros");
        assertThatThrownBy(() -> new PipelineFusedEvent(1L, 2_000_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
    }
}
