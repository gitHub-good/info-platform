package com.info.platform.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigFieldRules} 表驱动规则测试（T34）：各规则工厂的接受/拒绝边界与字段级错误消息（2001 PARAM_INVALID）， 供 T35~T37
 * 域校验器复用。
 */
class ConfigFieldRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 覆盖全部规则工厂的规则表（llm.global / datasource 域字段的超集）。 */
    private List<ConfigFieldRules.FieldRule> allRules() {
        return List.of(
                ConfigFieldRules.positiveLong("timeoutSeconds"),
                ConfigFieldRules.longRange("retry", 0, 3),
                ConfigFieldRules.nonNegativeDecimal("inputPricePerMillion"),
                ConfigFieldRules.decimalRange("budgetWarnRatio", 0, 1),
                ConfigFieldRules.bool("enabled"),
                ConfigFieldRules.nonBlank("model"),
                ConfigFieldRules.url("baseUrl"),
                ConfigFieldRules.oneOf("mode", "MOCK", "REAL"),
                ConfigFieldRules.positiveLongMap("cacheTtlSeconds"));
    }

    private JsonNode readTree(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void enforce_allFieldsValid_passes() {
        // Arrange
        String json =
                "{\"timeoutSeconds\":30,\"retry\":1,\"inputPricePerMillion\":1.0,"
                        + "\"budgetWarnRatio\":0.8,\"enabled\":true,\"model\":\"deepseek-flash\","
                        + "\"baseUrl\":\"https://api.deepseek.com\",\"mode\":\"REAL\","
                        + "\"cacheTtlSeconds\":{\"brief-type-1\":3600}}";

        // Act + Assert：全部字段合法 → 不抛
        assertThatCode(() -> ConfigFieldRules.enforce(readTree(json), allRules()))
                .doesNotThrowAnyException();
    }

    @Test
    void enforce_absentOptionalFields_pass() throws Exception {
        // Assert：可选字段缺失不报错（部分更新语义由域校验器合并后整体校验）
        assertThatCode(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{}"),
                                        List.of(ConfigFieldRules.positiveLong("timeoutSeconds"))))
                .doesNotThrowAnyException();
    }

    @Test
    void enforce_illegalValues_collectAllFieldReasonsWith2001() throws Exception {
        // Arrange：每个字段都非法
        String json =
                "{\"timeoutSeconds\":0,\"retry\":9,\"inputPricePerMillion\":-1,"
                        + "\"budgetWarnRatio\":1.5,\"enabled\":\"yes\",\"model\":\" \","
                        + "\"baseUrl\":\"ftp://x\",\"mode\":\"MOCKX\","
                        + "\"cacheTtlSeconds\":{\"brief-type-1\":0}}";

        // Act + Assert：一次收齐全部字段级原因（不首错即停），错误码 2001
        assertThatThrownBy(() -> ConfigFieldRules.enforce(readTree(json), allRules()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
                            assertThat(ex.getMessage())
                                    .contains("timeoutSeconds")
                                    .contains("retry")
                                    .contains("inputPricePerMillion")
                                    .contains("budgetWarnRatio")
                                    .contains("enabled")
                                    .contains("model")
                                    .contains("baseUrl")
                                    .contains("mode")
                                    .contains("cacheTtlSeconds");
                        });
    }

    @Test
    void boundaries_acceptedAtEdges_rejectedOutside() throws Exception {
        // Assert：边界接受——retry 0/3、ratio 上界 1（开闭区间）、单价 0 免费档；越界拒绝
        assertThatCode(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree(
                                                "{\"retry\":0,\"budgetWarnRatio\":1.0,\"inputPricePerMillion\":0}"),
                                        allRules()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConfigFieldRules.enforce(readTree("{\"retry\":3}"), allRules()))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"budgetWarnRatio\":0}"), allRules()))
                .hasMessageContaining("budgetWarnRatio");
        assertThatThrownBy(() -> ConfigFieldRules.enforce(readTree("{\"retry\":4}"), allRules()))
                .hasMessageContaining("retry");
    }

    @Test
    void requiredRule_missingFieldReportsMandatory() throws Exception {
        // Act + Assert：required 字段缺失报「必填」
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{}"),
                                        List.of(ConfigFieldRules.requiredNonBlank("model"))))
                .hasMessageContaining("model")
                .hasMessageContaining("必填");
    }

    @Test
    void urlRule_acceptsHttpAndHttps_rejectsOthers() throws Exception {
        assertThatCode(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"a\":\"http://x.cn\"}"),
                                        List.of(ConfigFieldRules.url("a"))))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"a\":\"https://x.cn\"}"),
                                        List.of(ConfigFieldRules.url("a"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"a\":\"https://x cn\"}"),
                                        List.of(ConfigFieldRules.url("a"))))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"a\":\"ftp://x.cn\"}"),
                                        List.of(ConfigFieldRules.url("a"))))
                .hasMessageContaining("http(s)");
    }

    @Test
    void typeMismatch_rejectedNotSilentlyCoerced() throws Exception {
        // Assert：字符串数字 / 浮点冒充整数不接受（严格类型，避免 SQLite 文本列宽松语义放走脏值）
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"timeoutSeconds\":\"30\"}"), allRules()))
                .hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(
                        () ->
                                ConfigFieldRules.enforce(
                                        readTree("{\"timeoutSeconds\":1.5}"), allRules()))
                .hasMessageContaining("timeoutSeconds");
    }
}
