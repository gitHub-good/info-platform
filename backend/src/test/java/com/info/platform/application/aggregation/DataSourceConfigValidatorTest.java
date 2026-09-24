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
        assertInvalid("datasource.QUOTE", json, expectedFragment);
    }

    private void assertInvalid(String configKey, String json, String expectedFragment) {
        assertThatThrownBy(() -> validator.validate(configKey, objectMapper.readTree(json)))
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
    void failureCacheTtl_optionalButValidatedWhenPresent() throws Exception {
        // P1-5b：可选字段——存量文档缺省通过（读取回落代码缺省），提供非法值拒绝
        String absent =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}";
        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(absent)))
                .doesNotThrowAnyException();
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"failureCacheTtlSeconds\":0,\"params\":{}}",
                "failureCacheTtlSeconds");
    }

    @Test
    void missingRequiredField_rejected() {
        assertInvalid("{\"mode\":\"REAL\",\"timeoutMillis\":1000}", "enabled");
    }

    @Test
    void backupSource_oneOfQuotedValues_onlyOnQuoteAndValuation() throws Exception {
        // ADR-0032：备选源开关热化——QUOTE/VALUATION 合法值 auto|eastmoney|tencent，其余源不允许该键
        String valid =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"backupSource\":\"tencent\"}}";
        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(valid)))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                validator.validate(
                                        "datasource.VALUATION", objectMapper.readTree(valid)))
                .doesNotThrowAnyException();
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"params\":{\"quoteUrl\":\"https://a.example.com\",\"backupSource\":\"sina\"}}",
                "backupSource");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "datasource.ANNOUNCE",
                                        objectMapper.readTree(
                                                "{\"enabled\":true,\"mode\":\"REAL\","
                                                        + "\"timeoutMillis\":2000,\"retries\":0,"
                                                        + "\"cacheTtlSeconds\":300,"
                                                        + "\"params\":{\"announceUrl\":\"https://a.example.com\","
                                                        + "\"backupSource\":\"auto\"}}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的字段");
    }

    @Test
    void announceReferer_validUrlParam_accepted() throws Exception {
        // ADR-0032 补齐：announceReferer 迁入 params 白名单（URL 规则同其他 referer）
        String json =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":300,"
                        + "\"params\":{\"announceUrl\":\"https://a.example.com\","
                        + "\"announceReferer\":\"https://data.eastmoney.com/\"}}";
        assertThatCode(() -> validator.validate("datasource.ANNOUNCE", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":300,"
                        + "\"params\":{\"announceReferer\":\"not-a-url\"}}",
                "announceReferer");
    }

    // —— ADR-0033 降级链校验 ——

    @Test
    void fallbackChain_validOrderedProviders_passes_quoteAndValuation() throws Exception {
        // 非空链：成员 ∈ 注册表且无重复（首元素即主源，顺序自由——可换主源）
        String json =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"fallbackChain\":[\"tencent\",\"eastmoney\"],"
                        + "\"params\":{}}";
        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                validator.validate(
                                        "datasource.VALUATION", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    @Test
    void fallbackChain_emptyArray_passes_primaryOnly() throws Exception {
        // 空链合法 = 仅主源（页面可清空备选）
        String json =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"fallbackChain\":[],\"params\":{}}";
        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    @Test
    void fallbackChain_absent_passes_readSideFoldsLegacy() throws Exception {
        // 字段可缺省（存量行无链——读取侧按旧 backupSource 折算/注册表全链兜底）
        String json =
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"params\":{}}";
        assertThatCode(() -> validator.validate("datasource.QUOTE", objectMapper.readTree(json)))
                .doesNotThrowAnyException();
    }

    @Test
    void fallbackChain_memberNotInRegistry_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"fallbackChain\":[\"eastmoney\",\"sina\"],"
                        + "\"params\":{}}",
                "不在源 QUOTE 可用 provider");
    }

    @Test
    void fallbackChain_duplicateMember_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,"
                        + "\"fallbackChain\":[\"eastmoney\",\"eastmoney\"],\"params\":{}}",
                "重复 provider");
    }

    @Test
    void fallbackChain_notAnArray_rejected() {
        assertInvalid(
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":1500,\"retries\":0,"
                        + "\"cacheTtlSeconds\":5,\"fallbackChain\":\"eastmoney\",\"params\":{}}",
                "须为字符串数组");
    }

    @Test
    void fallbackChain_singleProviderSource_rejectsSecondProvider() {
        // 单 provider 源注册表仅一项：备选成员自然被拒（页面呈现「暂无备选源」）
        assertInvalid(
                "datasource.FINANCE",
                "{\"enabled\":true,\"mode\":\"REAL\",\"timeoutMillis\":2000,\"retries\":0,"
                        + "\"cacheTtlSeconds\":3600,"
                        + "\"fallbackChain\":[\"eastmoney\",\"tencent\"],"
                        + "\"params\":{\"financeUrl\":\"https://a.example.com\"}}",
                "不在源 FINANCE 可用 provider");
    }
}
