package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * IncrementalReevalConfigValidator 单测（M22 T190，方案 §3.5-1）：incremental.reeval 保存侧防御——minScoreGap
 * 0~10 / linkMinIntervalMinutes 0~60 / scanWindowHours 1~72 / eventBufferSeconds 0~120 /
 * minImportance 枚举白名单； 非法抛 30092 字段级（多问题 "; " 连接），DB 原值保留继续生效。
 */
class IncrementalReevalConfigValidatorTest {

    private final IncrementalReevalConfigValidator validator =
            new IncrementalReevalConfigValidator();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void validate(String json) {
        try {
            validator.validate(
                    IncrementalReevalConfigValidator.CONFIG_KEY, objectMapper.readTree(json));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void supportsOnlyIncrementalReevalKey() {
        assertThat(validator.supports("incremental.reeval")).isTrue();
        assertThat(validator.supports("market.top")).isFalse();
        assertThat(validator.supports("job.INCREMENTAL_REEVAL")).isFalse();
    }

    @Test
    void validDocument_passes() {
        assertThatCode(
                        () ->
                                validate(
                                        "{\"minScoreGap\":0.5,\"linkMinIntervalMinutes\":10,"
                                                + "\"scanWindowHours\":24,\"eventBufferSeconds\":20,"
                                                + "\"minImportance\":\"HIGH\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void outOfRangeFields_rejectedWith30092FieldLevelMessages() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"minScoreGap\":11,\"linkMinIntervalMinutes\":61,"
                                                + "\"scanWindowHours\":73,\"eventBufferSeconds\":121,"
                                                + "\"minImportance\":\"HIGH\"}"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", ErrorCode.INCREMENTAL_REEVAL_CONFIG_INVALID)
                .hasMessageContaining("minScoreGap")
                .hasMessageContaining("linkMinIntervalMinutes")
                .hasMessageContaining("scanWindowHours")
                .hasMessageContaining("eventBufferSeconds");
    }

    @Test
    void missingFields_required() {
        assertThatThrownBy(() -> validate("{}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必填");
    }

    @Test
    void nonNumericRejected_stringDigitsNotSilentlyAccepted() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"minScoreGap\":\"0.5\",\"linkMinIntervalMinutes\":10,"
                                                + "\"scanWindowHours\":24,\"eventBufferSeconds\":20,"
                                                + "\"minImportance\":\"HIGH\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minScoreGap")
                .hasMessageContaining("数值");
    }

    @Test
    void unknownImportanceRejected() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"minScoreGap\":0.5,\"linkMinIntervalMinutes\":10,"
                                                + "\"scanWindowHours\":24,\"eventBufferSeconds\":20,"
                                                + "\"minImportance\":\"CRITICAL\"}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minImportance")
                .hasMessageContaining("HIGH");
    }
}
