package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmConfigValidator} 单测（T35，方案 §4.1 键空间表 + §6「表驱动校验全规则」）。
 *
 * <p>覆盖 llm.global（超时非正整数 / retry 越界 / 预算 0 / 阈值越界 / TTL 分档非法 / 必填缺失）与 llm.provider.*（model 空 / 单价负
 * / baseUrl 非法 / fallback 不存在或指向自身）两类键； 全部断言 2001 且 msg 带字段级原因（PRD「前后端双侧校验」红线）。
 */
class LlmConfigValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator =
                new LlmConfigValidator(
                        LlmConfigTest.configWith(
                                LlmConfigTest.deepseekProvider(), LlmConfigTest.glmProvider()));
    }

    @Test
    void supports_llmKeysOnly() {
        assertThat(validator.supports(ConfigCenter.KEY_LLM_GLOBAL)).isTrue();
        assertThat(validator.supports(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek")).isTrue();
        assertThat(validator.supports("llm.globalx")).isFalse();
        assertThat(validator.supports("datasource.QUOTE")).isFalse();
        assertThat(validator.supports("aggregation.global")).isFalse();
    }

    @Test
    void global_validSeedDocument_passes() {
        assertThatCode(() -> validator.validate(ConfigCenter.KEY_LLM_GLOBAL, validGlobal()))
                .doesNotThrowAnyException();
    }

    @Test
    void global_budgetZeroOrNegative_rejectedWithFieldReason() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_GLOBAL,
                                        MAPPER.readTree(
                                                """
                                                {"timeoutSeconds":30,"retry":1,"dailyTokenBudgetPerUser":0,
                                                 "budgetWarnRatio":0.8,"cacheDefaultTtlSeconds":3600}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("dailyTokenBudgetPerUser")
                .hasMessageContaining("正整数");
    }

    @Test
    void global_warnRatioOutOfRange_rejected() {
        // 0 与 1.5 均越界（合法 (0,1]）
        for (double ratio : new double[] {0, 1.5}) {
            assertThatThrownBy(
                            () ->
                                    validator.validate(
                                            ConfigCenter.KEY_LLM_GLOBAL,
                                            MAPPER.valueToTree(
                                                    Map.of(
                                                            "timeoutSeconds", 30,
                                                            "retry", 1,
                                                            "dailyTokenBudgetPerUser", 20000,
                                                            "budgetWarnRatio", ratio,
                                                            "cacheDefaultTtlSeconds", 3600))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("budgetWarnRatio");
        }
    }

    @Test
    void global_timeoutAndTtlMustBePositiveIntegers_retryBounded() {
        // timeoutSeconds 0 / retry 4 / cacheTtlSeconds 负值：一次收集全部字段级原因
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_GLOBAL,
                                        MAPPER.readTree(
                                                """
                                                {"timeoutSeconds":0,"retry":4,"dailyTokenBudgetPerUser":20000,
                                                 "budgetWarnRatio":0.8,"cacheDefaultTtlSeconds":-1,
                                                 "cacheTtlSeconds":{"brief-type-1":0}}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("timeoutSeconds")
                .hasMessageContaining("retry")
                .hasMessageContaining("cacheDefaultTtlSeconds")
                .hasMessageContaining("cacheTtlSeconds");
    }

    @Test
    void global_missingRequiredField_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_GLOBAL,
                                        MAPPER.readTree(
                                                """
                                                {"timeoutSeconds":30}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dailyTokenBudgetPerUser: 必填");
    }

    @Test
    void provider_validDocumentWithCipherFields_passes() {
        assertThatCode(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        validProvider()))
                .doesNotThrowAnyException();
    }

    @Test
    void provider_blankModelNegativePriceBadUrl_rejectedWithReasons() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"","enabled":true,"isDefault":true,"fallback":"glm",
                                                 "inputPricePerMillion":-1,"outputPricePerMillion":4,
                                                 "baseUrl":"ftp://x"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("model")
                .hasMessageContaining("inputPricePerMillion")
                .hasMessageContaining("baseUrl");
    }

    @Test
    void provider_fallbackUnknownProvider_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":true,"isDefault":true,
                                                 "fallback":"nonexistent","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fallback")
                .hasMessageContaining("nonexistent");
    }

    @Test
    void provider_fallbackSelfReference_rejected() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":true,"isDefault":true,
                                                 "fallback":"deepseek","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得指向自身");
    }

    private static com.fasterxml.jackson.databind.JsonNode validGlobal() {
        return MAPPER.valueToTree(
                Map.of(
                        "timeoutSeconds", 30,
                        "retry", 1,
                        "dailyTokenBudgetPerUser", 20000,
                        "budgetWarnRatio", 0.8,
                        "cacheDefaultTtlSeconds", 3600,
                        "cacheTtlSeconds", Map.of("brief-type-1", 3600)));
    }

    private static com.fasterxml.jackson.databind.JsonNode validProvider() {
        return MAPPER.valueToTree(
                Map.of(
                        "model", "deepseek-flash",
                        "enabled", true,
                        "isDefault", true,
                        "fallback", "glm",
                        "inputPricePerMillion", 1.0,
                        "outputPricePerMillion", 4.0,
                        "baseUrl", "https://api.deepseek.com",
                        "apiKeyCipher", "base64cipher",
                        "apiKeyLast4", "abcd"));
    }
}
