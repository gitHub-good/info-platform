package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * IndustryMainlineConfigValidator 单测（M27 T243，方案 §4.3.3——保存侧防御）：两键字段级 30096——必填 / 类型 / 范围 / 权重和
 * 1±0.001 / 门槛死局守卫；合法文档零异常（DB 原值保留语义由 RuntimeConfigService 保证）。
 */
class IndustryMainlineConfigValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IndustryMainlineConfigValidator validator = new IndustryMainlineConfigValidator();

    private com.fasterxml.jackson.databind.JsonNode doc(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void supports_bothKeys() {
        assertThat(validator.supports("industry.mainline")).isTrue();
        assertThat(validator.supports("industry.leader")).isTrue();
        assertThat(validator.supports("score.weight")).isFalse();
    }

    @Test
    void validate_mainlineDefaults_passes() throws Exception {
        assertThatCode(
                        () ->
                                validator.validate(
                                        "industry.mainline",
                                        doc(
                                                "{\"wp\":0.40,\"wh\":0.35,\"we\":0.25,"
                                                        + "\"priceWinDay\":0.5,\"priceWinD5\":0.5,"
                                                        + "\"heatH24\":0.5,\"heatD7\":0.3,\"heatDelta\":0.2,"
                                                        + "\"topN\":5,\"persistMinDays\":2,"
                                                        + "\"persistWindowDays\":5,\"topThirdRank\":10,"
                                                        + "\"divergenceHeatRank\":13}")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_leaderDefaults_passes() throws Exception {
        assertThatCode(
                        () ->
                                validator.validate(
                                        "industry.leader",
                                        doc(
                                                "{\"wa\":0.50,\"wv\":0.35,\"wq\":0.15,"
                                                        + "\"mentionDays\":7,\"topN\":3,"
                                                        + "\"qDay\":0.5,\"qD5\":0.5}")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_weightSumOff_rejectedAs30096FieldLevel() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.mainline",
                                        doc(
                                                "{\"wp\":0.50,\"wh\":0.35,\"we\":0.25,"
                                                        + "\"priceWinDay\":0.5,\"priceWinD5\":0.5,"
                                                        + "\"heatH24\":0.5,\"heatD7\":0.3,"
                                                        + "\"heatDelta\":0.2,\"topN\":5,"
                                                        + "\"persistMinDays\":2,"
                                                        + "\"persistWindowDays\":5,"
                                                        + "\"topThirdRank\":10,"
                                                        + "\"divergenceHeatRank\":13}")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_MAINLINE_CONFIG_INVALID))
                .hasMessageContaining("wp+wh+we")
                .hasMessageContaining("1±0.001");
    }

    @Test
    void validate_subWeightSumOff_rejected() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.leader",
                                        doc(
                                                "{\"wa\":0.50,\"wv\":0.35,\"wq\":0.15,"
                                                        + "\"mentionDays\":7,\"topN\":3,"
                                                        + "\"qDay\":0.6,\"qD5\":0.5}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("qDay+qD5");
    }

    @Test
    void validate_missingField_rejectedAsRequired() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.mainline",
                                        doc(
                                                "{\"wp\":0.40,\"wh\":0.35,\"we\":0.25,"
                                                        + "\"priceWinDay\":0.5,\"priceWinD5\":0.5,"
                                                        + "\"heatH24\":0.5,\"heatD7\":0.3,"
                                                        + "\"heatDelta\":0.2,\"topN\":5}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("persistMinDays: 必填");
    }

    @Test
    void validate_stringNumber_rejectedAsType() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.leader",
                                        doc(
                                                "{\"wa\":\"0.5\",\"wv\":0.35,\"wq\":0.15,"
                                                        + "\"mentionDays\":7,\"topN\":3,"
                                                        + "\"qDay\":0.5,\"qD5\":0.5}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wa: 须为数值");
    }

    @Test
    void validate_topNOutOfRange_rejected() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.mainline",
                                        doc(
                                                "{\"wp\":0.40,\"wh\":0.35,\"we\":0.25,"
                                                        + "\"priceWinDay\":0.5,\"priceWinD5\":0.5,"
                                                        + "\"heatH24\":0.5,\"heatD7\":0.3,"
                                                        + "\"heatDelta\":0.2,\"topN\":6,"
                                                        + "\"persistMinDays\":2,"
                                                        + "\"persistWindowDays\":5,"
                                                        + "\"topThirdRank\":10,"
                                                        + "\"divergenceHeatRank\":13}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("topN: 须在 3 ~ 5");
    }

    @Test
    void validate_persistMinAboveWindow_deadlockRejected() throws Exception {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "industry.mainline",
                                        doc(
                                                "{\"wp\":0.40,\"wh\":0.35,\"we\":0.25,"
                                                        + "\"priceWinDay\":0.5,\"priceWinD5\":0.5,"
                                                        + "\"heatH24\":0.5,\"heatD7\":0.3,"
                                                        + "\"heatDelta\":0.2,\"topN\":5,"
                                                        + "\"persistMinDays\":6,"
                                                        + "\"persistWindowDays\":5,"
                                                        + "\"topThirdRank\":10,"
                                                        + "\"divergenceHeatRank\":13}")))
                .hasMessageContaining("persistMinDays: 不得大于 persistWindowDays");
    }
}
