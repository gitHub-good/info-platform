package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@link DataSourceConfigValidator} 表驱动校验测试（T36）：合法文档通过；超时/TTL 非正整数、retries 越界、mode 非法、URL
 * 非法格式、条数非正整数、未知 params 键、EVENT 携带外呼参数、缺必填字段 → 2001 字段级原因。
 */
class DataSourceConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSourceConfigValidator validator = new DataSourceConfigValidator();

    private void assertInvalid(String json, String expectedFragment) {
        assertThatThrownBy(
                        () -> validator.validate("datasource.QUOTE", objectMapper.readTree(json)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> {
                            assertThat(((BusinessException) e).getErrorCode())
                                    .isEqualTo(ErrorCode.PARAM_INVALID);
                            assertThat(e.getMessage()).contains(expectedFragment);
                        });
    }

    @Test
    void validQuoteDocument_passes() throws Exception {
        String json =
                """
                {"enabled":true,"mode":"REAL","timeoutMillis":1500,"retries":0,
                 "cacheTtlSeconds":5,
                 "params":{"quoteUrl":"https://push2.eastmoney.com/api/qt/stock/get","fields":"f43,f57"}}
                """;

        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    @Test
    void supports_onlyDatasourceKeys() {
        assertThat(validator.supports("datasource.QUOTE")).isTrue();
        assertThat(validator.supports("datasource.EVENT")).isTrue();
        assertThat(validator.supports("datasource.UNKNOWN")).isFalse();
        assertThat(validator.supports("llm.global")).isFalse();
        assertThat(validator.supports(null)).isFalse();
    }

    @Test
    void timeoutZeroOrNegative_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":0,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}",
                "timeoutMillis");
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":-100,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}",
                "timeoutMillis");
    }

    @Test
    void retriesOutOfRange_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1000,\"retries\":4,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}",
                "retries");
    }

    @Test
    void modeNotInEnum_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"FOO\",\"timeoutMillis\":1000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}",
                "mode");
    }

    @Test
    void cacheTtlNotPositive_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"MOCK\",\"timeoutMillis\":1000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":0,\"params\":{}}",
                "cacheTtlSeconds");
    }

    @Test
    void malformedUrlParam_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"ftp://not-http.example.com\"}}",
                "quoteUrl");
    }

    @Test
    void nonPositiveCountParam_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":300,"
                        + "\"params\":{\"announceUrl\":\"https://a.example.com\",\"announcePageSize\":0}}",
                "announcePageSize");
    }

    @Test
    void unknownParamKey_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"ttlHack\":1}}",
                "ttlHack");
    }

    @Test
    void eventSource_withParams_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":30,\"params\":{\"url\":\"https://a.example.com\"}}",
                "不支持的字段");
    }

    @Test
    void eventSource_emptyParams_passes() throws Exception {
        String json =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":30,\"params\":{}}";

        assertThatCode(() -> validator.validate("datasource.EVENT", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredField_rejected() {
        assertInvalid("{\"mode\":\"REAL\",\"timeoutMillis\":1000}", "enabled");
    }
}
