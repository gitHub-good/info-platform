package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import org.junit.jupiter.api.Test;

/** {@link AggregationGlobalConfigValidator} 测试（T34）：detailTimeoutMillis 500~10000 闭区间与键匹配。 */
class AggregationGlobalConfigValidatorTest {

    private final AggregationGlobalConfigValidator validator =
            new AggregationGlobalConfigValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void supports_onlyAggregationGlobalKey() {
        assertThat(validator.supports("aggregation.global")).isTrue();
        assertThat(validator.supports("aggregation.other")).isFalse();
        assertThat(validator.supports("llm.global")).isFalse();
    }

    @Test
    void validate_acceptsRangeEdges() throws Exception {
        assertThatCode(
                        () ->
                                validator.validate(
                                        AggregationGlobalConfigValidator.KEY,
                                        mapper.readTree("{\"detailTimeoutMillis\":500}")))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                validator.validate(
                                        AggregationGlobalConfigValidator.KEY,
                                        mapper.readTree("{\"detailTimeoutMillis\":10000}")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsOutOfRangeWithFieldReason() throws Exception {
        // Assert：下限 499 / 上限 10001 均拒绝，msg 带字段级原因
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        AggregationGlobalConfigValidator.KEY,
                                        mapper.readTree("{\"detailTimeoutMillis\":499}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("detailTimeoutMillis");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        AggregationGlobalConfigValidator.KEY,
                                        mapper.readTree("{\"detailTimeoutMillis\":10001}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500~10000");
    }

    @Test
    void validate_absentFieldPasses_partialUpdateSemantics() throws Exception {
        assertThatCode(
                        () ->
                                validator.validate(
                                        AggregationGlobalConfigValidator.KEY,
                                        mapper.readTree("{}")))
                .doesNotThrowAnyException();
    }
}
