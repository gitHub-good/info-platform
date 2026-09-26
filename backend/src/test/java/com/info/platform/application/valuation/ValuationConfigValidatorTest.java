package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ValuationConfigValidator 单测（T172，方案 §4.3/§4.7.3 + §6 权重校验负向清单）：合法文档放行；负权重/越界
 * 权重/全零/Σ&gt;1.05/窗口越界/hl 与 K 越界/阈值越界/类型损坏 → 30087 字段级（多问题 "; " 连接），原值保留由写路径保证。
 */
class ValuationConfigValidatorTest {

    private ValuationConfigValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new ValuationConfigValidator();
    }

    private JsonNode doc(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    /** §4.3 冻结缺省全文档（合法基准）。 */
    private static String defaultsJson() {
        return "{\"wCatalyst\":0.40,\"wConduction\":0.20,\"wFundamental\":0.20,\"wRisk\":0.20,"
                + "\"wValuation\":0.00,\"catalystWindowDays\":10,\"assocWindowDays\":30,"
                + "\"halfLifeDays\":5.0,\"k1Saturation\":3.0,\"k3Saturation\":1.5,"
                + "\"btCatalystMin\":60,\"btConductionMin\":50,\"btRiskMin\":80}";
    }

    private static String with(String field, String value) {
        // 保持其余字段缺省合法，只替换目标字段（json 内字段唯一，直接文本替换足够）
        return defaultsJson()
                .replaceAll("\"" + field + "\":[0-9.\\-]+", "\"" + field + "\":" + value);
    }

    @Test
    void supports_onlyScoreWeightKey() {
        assertThat(validator.supports("score.weight")).isTrue();
        assertThat(validator.supports("retention.global")).isFalse();
    }

    @Test
    void defaultsDocument_passes() throws Exception {
        assertThatCode(() -> validator.validate("score.weight", doc(defaultsJson())))
                .doesNotThrowAnyException();
    }

    @Test
    void unnormalizedWeightsWithinTolerance_pass() throws Exception {
        // 合成自动归一（§4.2 除以 Σw）：Σ=0.9 ≤ 1.05 容差内放行
        assertThatCode(
                        () ->
                                validator.validate(
                                        "score.weight",
                                        doc(
                                                with("wCatalyst", "0.30")
                                                        .replaceAll(
                                                                "\"wRisk\":0.20",
                                                                "\"wRisk\":0.10"))))
                .doesNotThrowAnyException();
    }

    @Test
    void negativeWeight_rejected30087FieldLevel() throws Exception {
        assertThatThrownBy(() -> validator.validate("score.weight", doc(with("wCatalyst", "-0.1"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> {
                            assertThat(((BusinessException) e).getErrorCode())
                                    .isEqualTo(ErrorCode.VALUATION_CONFIG_INVALID);
                            assertThat(e.getMessage()).contains("wCatalyst");
                        });
    }

    @Test
    void weightAboveOne_rejected() throws Exception {
        assertThatThrownBy(() -> validator.validate("score.weight", doc(with("wValuation", "1.2"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wValuation");
    }

    @Test
    void allZeroWeights_rejected() throws Exception {
        String allZero =
                "{\"wCatalyst\":0,\"wConduction\":0,\"wFundamental\":0,\"wRisk\":0,\"wValuation\":0,"
                        + "\"catalystWindowDays\":10,\"assocWindowDays\":30,\"halfLifeDays\":5.0,"
                        + "\"k1Saturation\":3.0,\"k3Saturation\":1.5,"
                        + "\"btCatalystMin\":60,\"btConductionMin\":50,\"btRiskMin\":80}";
        assertThatThrownBy(() -> validator.validate("score.weight", doc(allZero)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权重和");
    }

    @Test
    void weightSumAboveTolerance_rejected() throws Exception {
        // Σ=1.1 > 1.05 归一容差 → 拒绝（§6 负向清单「Σ>1.05」）
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight",
                                        doc(
                                                with("wCatalyst", "0.50")
                                                        .replaceAll(
                                                                "\"wRisk\":0.20",
                                                                "\"wRisk\":0.30"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权重和");
    }

    @Test
    void windowsOutOfBounds_rejectedFieldLevel() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight", doc(with("catalystWindowDays", "4"))))
                .hasMessageContaining("catalystWindowDays");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight", doc(with("catalystWindowDays", "31"))))
                .hasMessageContaining("catalystWindowDays");
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("assocWindowDays", "9"))))
                .hasMessageContaining("assocWindowDays");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight", doc(with("assocWindowDays", "61"))))
                .hasMessageContaining("assocWindowDays");
    }

    @Test
    void halfLifeAndSaturationOutOfBounds_rejected() throws Exception {
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("halfLifeDays", "0.5"))))
                .hasMessageContaining("halfLifeDays");
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("halfLifeDays", "15.1"))))
                .hasMessageContaining("halfLifeDays");
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("k1Saturation", "0.4"))))
                .hasMessageContaining("k1Saturation");
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("k3Saturation", "10.5"))))
                .hasMessageContaining("k3Saturation");
    }

    @Test
    void breakthroughThresholdsOutOfBounds_rejected() throws Exception {
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("btCatalystMin", "-1"))))
                .hasMessageContaining("btCatalystMin");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight", doc(with("btConductionMin", "101"))))
                .hasMessageContaining("btConductionMin");
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("btRiskMin", "100.5"))))
                .hasMessageContaining("btRiskMin");
    }

    @Test
    void missingField_rejectedAsRequired() throws Exception {
        String missingWeight = defaultsJson().replaceAll("\"wRisk\":0\\.20,", "");
        assertThatThrownBy(() -> validator.validate("score.weight", doc(missingWeight)))
                .hasMessageContaining("wRisk: 必填");
    }

    @Test
    void wrongType_rejectedNotCoerced() throws Exception {
        // 字符串数字不静默采信（D3 同因：类型判定单一事实源在校验器）
        assertThatThrownBy(
                        () -> validator.validate("score.weight", doc(with("wCatalyst", "\"0.4\""))))
                .hasMessageContaining("wCatalyst")
                .hasMessageContaining("数值");
        assertThatThrownBy(() -> validator.validate("score.weight", doc(with("btRiskMin", "80.5"))))
                .hasMessageContaining("btRiskMin")
                .hasMessageContaining("整数");
    }

    @Test
    void multipleProblems_joinedWithSemicolon() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "score.weight",
                                        doc(
                                                with("wCatalyst", "-1")
                                                        .replaceAll(
                                                                "\"btRiskMin\":80",
                                                                "\"btRiskMin\":120"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> {
                            String msg = e.getMessage();
                            assertThat(msg).contains("wCatalyst");
                            assertThat(msg).contains("btRiskMin");
                            assertThat(msg).contains("; ");
                        });
    }

    @Test
    void unknownExtraField_ignored() throws Exception {
        // 读侧忽略未知字段（retention 惯例）——basis 等派生字段混入不拒绝
        assertThatCode(
                        () ->
                                validator.validate(
                                        "score.weight",
                                        doc(
                                                defaultsJson()
                                                        .replaceAll(
                                                                "\\}$", ",\"basis\":\"stale\"}"))))
                .doesNotThrowAnyException();
    }
}
