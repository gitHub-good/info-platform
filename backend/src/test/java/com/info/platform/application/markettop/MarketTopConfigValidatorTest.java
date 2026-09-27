package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * MarketTopConfigValidator 单测（M21 T181，方案 §4.7.3 保存侧防御）：字段区间矩阵 / 缺失与非数值类型字段级拦截（deepDiveLimit=60
 * 越界——蓝图区间 30~50 硬校验语义）/ 合法全量放行。
 */
class MarketTopConfigValidatorTest {

    private final MarketTopConfigValidator validator = new MarketTopConfigValidator();

    private final ObjectMapper mapper = new ObjectMapper();

    private void validate(String json) {
        try {
            validator.validate(MarketTopConfigValidator.CONFIG_KEY, mapper.readTree(json));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validDocument_passes() {
        validate(
                "{\"poolSize\":300,\"deepDiveLimit\":40,\"deepDiveCostCapRatio\":0.30,"
                        + "\"diveCostEstimateMicros\":100000,\"memberCoverageFloor\":0.80}");
    }

    @Test
    void deepDiveLimitAboveFifty_rejected_blueprintHardBound() {
        // §4.3.4：deepDiveLimit 30~50 硬校验——「全量 LLM 逐股永不发生」的配置面防线（60 拦截）
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":60,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.MARKET_TOP_CONFIG_INVALID))
                .hasMessageContaining("deepDiveLimit")
                .hasMessageContaining("30 ~ 50");
    }

    @Test
    void boundsMatrix_fieldLevelRejections() {
        // poolSize 99 / 801、deepDiveLimit 29 / 51、capRatio 0.04 / 1.01、floor -0.01 / 1.01、estimate
        // 0
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":99,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("poolSize: 须在 100 ~ 800");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":801,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("poolSize: 须在 100 ~ 800");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":29,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("deepDiveLimit: 须在 30 ~ 50");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.04,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("deepDiveCostCapRatio: 须在 0.05 ~ 1.0");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":0,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("diveCostEstimateMicros");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":1.01}"))
                .hasMessageContaining("memberCoverageFloor: 须在 0.0 ~ 1.0");
    }

    @Test
    void missingOrNonNumericFields_fieldLevelMessages() {
        // 缺字段「必填」/ 字符串数字不静默采信（D3：类型判定单一事实源在校验器）
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"deepDiveLimit\":40,\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("poolSize: 必填");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":\"300\",\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("poolSize: 须为整数");
        assertThatThrownBy(
                        () ->
                                validate(
                                        "{\"poolSize\":300,\"deepDiveLimit\":40.5,"
                                                + "\"deepDiveCostCapRatio\":0.30,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.80}"))
                .hasMessageContaining("deepDiveLimit: 须为整数");
    }
}
