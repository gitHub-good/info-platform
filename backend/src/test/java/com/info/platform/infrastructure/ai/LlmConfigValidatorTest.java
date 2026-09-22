package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link LlmConfigValidator} 单测（T35，方案 §4.1 键空间表 + §6「表驱动校验全规则」）。
 *
 * <p>覆盖 llm.global（超时非正整数 / retry 越界 / 预算 0 / 阈值越界 / TTL 分档非法 / 必填缺失）与 llm.provider.*（model 空 / 单价负
 * / baseUrl 非法 / fallback 不存在或指向自身）两类键； 全部断言 2001 且 msg 带字段级原因（PRD「前后端双侧校验」红线）。
 *
 * <p><b>DEFECT-3 回归（M4）</b>：fallback 交叉校验——指向停用 provider 被拒（键空间表「存在且 enabled」）、 停用被启用 provider 引用的
 * provider 被拒（提示先解除引用）、休眠引用（引用方停用）不阻塞。
 */
class LlmConfigValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-22T01:00:00Z");

    /** 内存仓储：跨校验状态（其他 provider 的运行时键）经它生效（种子语义直落库，绕校验器）。 */
    private final InMemoryRepository repository = new InMemoryRepository();

    private LlmConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator =
                newValidator(
                        LlmConfigTest.configWith(
                                LlmConfigTest.deepseekProvider(), LlmConfigTest.glmProvider()));
    }

    /** 校验器 + 只读配置服务（快照读运行时键；写路径不在本测试范围）。 */
    private LlmConfigValidator newValidator(LlmConfig config) {
        RuntimeConfigService configService =
                new RuntimeConfigService(
                        repository,
                        List.of(),
                        event -> {},
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        MAPPER);
        return new LlmConfigValidator(config, fixedProvider(configService));
    }

    /** 固定解析的 ObjectProvider 桩（复刻 Spring 对构造环的 lazy 解析语义，同包 facade 测试复用）。 */
    static ObjectProvider<RuntimeConfigService> fixedProvider(RuntimeConfigService service) {
        return lazyProvider(() -> service);
    }

    static ObjectProvider<RuntimeConfigService> lazyProvider(
            Supplier<RuntimeConfigService> source) {
        return new ObjectProvider<>() {
            @Override
            public RuntimeConfigService getObject() {
                return source.get();
            }

            @Override
            public RuntimeConfigService getObject(Object... args) {
                return source.get();
            }

            @Override
            public RuntimeConfigService getIfAvailable() {
                return source.get();
            }

            @Override
            public RuntimeConfigService getIfUnique() {
                return source.get();
            }
        };
    }

    private void seedRuntimeRow(String configKey, String json) {
        repository.save(RuntimeConfig.create(configKey, json, null, NOW));
    }

    /** 内存仓储（真实落库语义，供交叉校验读其他 provider 的运行时键）。 */
    private static class InMemoryRepository implements RuntimeConfigRepository {
        final Map<String, RuntimeConfig> rows = new LinkedHashMap<>();

        @Override
        public Optional<RuntimeConfig> findByKey(String configKey) {
            return Optional.ofNullable(rows.get(configKey));
        }

        @Override
        public List<RuntimeConfig> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public RuntimeConfig save(RuntimeConfig config) {
            rows.put(config.getConfigKey(), config);
            return config;
        }
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

    @Test
    void provider_fallbackTargetDisabledInYml_rejected() {
        // DEFECT-3 复现 a（修前红）：键空间表「fallback 须为存在且 enabled 的 provider」——
        // 目标 provider 停用（yml 缺省即停，同生产 kimi/qwen 形态）时写入须拒绝，静默接受=失去灾备
        LlmConfig.Provider glm = LlmConfigTest.glmProvider();
        glm.setEnabled(false);
        validator = newValidator(LlmConfigTest.configWith(LlmConfigTest.deepseekProvider(), glm));

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":true,"isDefault":true,
                                                 "fallback":"glm","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fallback")
                .hasMessageContaining("停用");
    }

    @Test
    void provider_fallbackTargetDisabledByRuntimeRow_rejected() {
        // 页面已停用目标（运行时键覆盖 yml 缺省）同样拒绝：目标状态读当前生效配置
        seedRuntimeRow(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "glm",
                """
                {"model":"glm-4-flash-250414","enabled":false,"isDefault":false,"fallback":"deepseek",
                 "inputPricePerMillion":0,"outputPricePerMillion":0,"baseUrl":"https://open.bigmodel.cn"}
                """);

        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":true,"isDefault":true,
                                                 "fallback":"glm","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fallback")
                .hasMessageContaining("停用");
    }

    @Test
    void provider_fallbackTargetEnabledByRuntimeRow_accepted() {
        // 对照：目标 yml 缺省停用、但运行时键已启用 → 允许指向（enabled 读当前生效配置，非静态 yml）
        seedRuntimeRow(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "glm",
                """
                {"model":"glm-4-flash-250414","enabled":true,"isDefault":false,"fallback":"deepseek",
                 "inputPricePerMillion":0,"outputPricePerMillion":0,"baseUrl":"https://open.bigmodel.cn"}
                """);
        LlmConfig.Provider glm = LlmConfigTest.glmProvider();
        glm.setEnabled(false);

        assertThatCode(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":true,"isDefault":true,
                                                 "fallback":"glm","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .doesNotThrowAnyException();
    }

    @Test
    void provider_disableReferencedByEnabledProvider_rejected() {
        // DEFECT-3 复现 b 的处置路径（修前红）：停用被启用中 provider（glm.fallback=deepseek）引用的
        // provider 须明确拒绝并点名引用方——提示先解除引用（单 PUT 无法原子改两条记录的合理闭环）
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":false,"isDefault":true,
                                                 "fallback":"glm","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("enabled")
                .hasMessageContaining("glm")
                .hasMessageContaining("fallback");
    }

    @Test
    void provider_disableOnlyReferencedByDormantProvider_accepted() {
        // 不扩大拒绝面：引用方自身停用（休眠引用，运行时链不经过）不阻塞停用
        LlmConfig.Provider deepseek = LlmConfigTest.deepseekProvider();
        LlmConfig.Provider glm = LlmConfigTest.glmProvider();
        glm.setFallback(null); // 解除 glm 对 deepseek 的引用
        LlmConfig.Provider dormant =
                LlmConfigFacadeImplTest.qwenProvider(); // enabled=false，fallback=deepseek
        validator = newValidator(LlmConfigTest.configWith(deepseek, glm, dormant));

        assertThatCode(
                        () ->
                                validator.validate(
                                        ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                                        MAPPER.readTree(
                                                """
                                                {"model":"deepseek-flash","enabled":false,"isDefault":true,
                                                 "fallback":"glm","baseUrl":"https://api.deepseek.com"}
                                                """)))
                .doesNotThrowAnyException();
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
