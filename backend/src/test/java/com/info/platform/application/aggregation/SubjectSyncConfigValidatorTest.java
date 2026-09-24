package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@link SubjectSyncConfigValidator} 校验测试（ADR-0032）：键 {@code subject.sync} 精确匹配； aShareSource 可选
 * oneOf（auto|eastmoney|sina），缺失/空文档通过（部分更新语义），非法值 2001 字段级原因。
 */
class SubjectSyncConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubjectSyncConfigValidator validator = new SubjectSyncConfigValidator();

    @Test
    void supports_onlyExactKey() {
        assertThat(validator.supports("subject.sync")).isTrue();
        assertThat(validator.supports("subject.syncx")).isFalse();
        assertThat(validator.supports("job.SUBJECT_SYNC")).isFalse();
        assertThat(validator.supports(null)).isFalse();
    }

    @Test
    void validValues_pass() throws Exception {
        for (String value : new String[] {"auto", "eastmoney", "sina"}) {
            String json = "{\"aShareSource\":\"" + value + "\"}";
            assertThatCode(() -> validator.validate("subject.sync", objectMapper.readTree(json)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void absentField_passes_partialUpdateSemantics() throws Exception {
        assertThatCode(() -> validator.validate("subject.sync", objectMapper.readTree("{}")))
                .doesNotThrowAnyException();
    }

    @Test
    void illegalValue_rejectedWithFieldReason() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree("{\"aShareSource\":\"tencent\"}")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> {
                            assertThat(((BusinessException) e).getErrorCode())
                                    .isEqualTo(ErrorCode.PARAM_INVALID);
                            assertThat(e.getMessage()).contains("aShareSource");
                        });
    }

    // —— ADR-0033 降级链字段 ——

    @Test
    void fallbackChain_validProviders_pass() throws Exception {
        assertThatCode(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree(
                                                "{\"fallbackChain\":[\"eastmoney\",\"sina\"]}")))
                .doesNotThrowAnyException();
        // 空链合法 = 仅主源；字段可缺省（存量行兼容）
        assertThatCode(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree("{\"fallbackChain\":[]}")))
                .doesNotThrowAnyException();
    }

    @Test
    void fallbackChain_memberNotInListRegistry_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree(
                                                "{\"fallbackChain\":[\"eastmoney\",\"tencent\"]}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在 A 股列表源可用 provider");
    }

    @Test
    void fallbackChain_duplicateMember_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree(
                                                "{\"fallbackChain\":[\"sina\",\"sina\"]}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复 provider");
    }

    @Test
    void fallbackChain_notAnArray_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "subject.sync",
                                        objectMapper.readTree("{\"fallbackChain\":\"auto\"}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("须为字符串数组");
    }
}
