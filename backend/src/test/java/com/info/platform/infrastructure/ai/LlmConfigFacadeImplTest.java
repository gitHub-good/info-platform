package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.LlmConfigFacade;
import com.info.platform.application.ai.LlmConfigFacade.LlmConnectivityResult;
import com.info.platform.application.ai.LlmConfigView;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.domain.common.UserContext;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.ConfigSecretCipher;
import com.info.platform.infrastructure.common.RuntimeLlmProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link LlmConfigFacadeImpl} 单测（T35，方案 §4.4.1 + §6 测试要点）。
 *
 * <p>真实链路：{@link RuntimeConfigService}（内存仓储 + 真校验器）+ 真实 {@link ConfigCenter} 快照 + 真实 {@link
 * ConfigSecretCipher}（AES-256-GCM 往返），adapter/留痕 Mockito mock。覆盖：GET 脱敏视图与逐字段 effectiveMode、PATCH
 * 合并语义与 30065 防呆、isDefault 互斥恰一、单价校验 2001、key 加密写入往返 + 30064 降级 + DB&gt;ENV 解析、连通性测试成功/失败/无
 * adapter/30060。
 */
class LlmConfigFacadeImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T01:00:00Z");
    private static final long USER_ID = 1001L;
    private static final String SECRET = "unit-test-secret-0123456789abcdef012345";

    /** 内存仓储：真实落库语义（键覆盖、描述/创建时间沿用），供快照/写路径走真实流程。 */
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryRepository repository = new InMemoryRepository();
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private RuntimeConfigService configService;
    private ConfigCenter configCenter;
    private LlmCostGuard costGuard;
    private LlmProviderAdapter deepseekAdapter;
    private LlmCallLogger callLog;
    private ExecutorService executor;
    private LlmConfigFacadeImpl facade;

    @BeforeEach
    void setUp() {
        configService =
                new RuntimeConfigService(
                        repository,
                        // 校验器惰性持服务引用（交叉校验读运行时键，DEFECT-3）：lambda 读字段为调用时点值
                        List.of(
                                new LlmConfigValidator(
                                        LlmConfigValidatorTest.lazyProvider(() -> configService))),
                        eventPublisher,
                        clock,
                        objectMapper);
        List<RuntimeConfigSeed> seeds =
                new com.info.platform.infrastructure.common.LlmRuntimeConfigSeeder(objectMapper)
                        .seeds();
        configService.seedIfAbsent(seeds);
        ConfigSecretCipher cipher = new ConfigSecretCipher(SECRET);
        configCenter = new ConfigCenter(configService, List.of(), cipher, objectMapper);
        costGuard = new LlmCostGuard(ConfigCenterStubs::globalOfDefaults, clock);
        deepseekAdapter = mock(LlmProviderAdapter.class);
        when(deepseekAdapter.name()).thenReturn("deepseek");
        callLog = mock(LlmCallLogger.class);
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        facade =
                new LlmConfigFacadeImpl(
                        configService,
                        configCenter,
                        cipher,
                        List.of(deepseekAdapter),
                        callLog,
                        costGuard,
                        executor,
                        objectMapper);
        UserContext.set(new UserContext.Principal(USER_ID, "tester"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        UserContext.clear();
    }

    /** 环境变量注入桩体（允许受检异常，由 withEnvKey 归一为 IllegalStateException）。 */
    interface EnvBody {
        void run() throws Exception;
    }

    /** ENV key 注入桩：stub {@link LlmDefaults#envApiKey}（ENV 读取唯一收敛点；System 不可 mock），其余静态真调。 */
    private static void withEnvKey(String providerName, String value, EnvBody body) {
        try (MockedStatic<LlmDefaults> defaults =
                mockStatic(LlmDefaults.class, CALLS_REAL_METHODS)) {
            defaults.when(() -> LlmDefaults.envApiKey(providerName)).thenReturn(value);
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void view_returnsMaskedKeyStatePerProviderAndEffectiveModes() {
        // ENV key 经 System.getenv 解析（ADR-0020 后无 yml 段，一等来源不动）→ deepseek ENV 态
        withEnvKey(
                "deepseek",
                "sk-env-test-key",
                () -> {
                    LlmConfigView view = facade.view();

                    // 全局：种子值 + 只读展示项 + 逐字段生效级别（baseUrl/容量 RESTART，其余 LIVE）
                    assertThat(view.global().timeoutSeconds()).isEqualTo(30L);
                    assertThat(view.global().dailyTokenBudgetPerUser()).isEqualTo(20000L);
                    assertThat(view.global().cacheMaximumSize()).isEqualTo(1000L);
                    assertThat(view.global().updatedAt()).isEqualTo(NOW.toString());
                    assertThat(view.global().effectiveModes())
                            .containsEntry("cacheMaximumSize", "RESTART")
                            .containsEntry("dailyTokenBudgetPerUser", "LIVE");

                    // provider：key 脱敏（deepseek ENV / qwen 与 kimi NOT_SET）；baseUrl 契约字段
                    assertThat(view.providers()).hasSize(4);
                    LlmConfigView.ProviderConfigView deepseek = providerOf(view, "deepseek");
                    assertThat(deepseek.apiKey().status())
                            .isEqualTo(LlmConfigView.ApiKeyView.STATUS_CONFIGURED);
                    assertThat(deepseek.apiKey().source()).isEqualTo("ENV");
                    assertThat(deepseek.apiKey().last4()).isEqualTo("-key");
                    assertThat(deepseek.baseUrlEffective()).isEqualTo("RESTART");
                    assertThat(deepseek.effectiveModes())
                            .containsEntry("baseUrl", "RESTART")
                            .containsEntry("model", "LIVE");
                    assertThat(providerOf(view, "qwen").apiKey().status())
                            .isEqualTo(LlmConfigView.ApiKeyView.STATUS_NOT_SET);
                    assertThat(providerOf(view, "kimi").apiKey().status())
                            .isEqualTo(LlmConfigView.ApiKeyView.STATUS_NOT_SET);
                    assertThat(view.apiKeyWriteEnabled()).isTrue();
                });
    }

    @Test
    void updateGlobal_mergesOnlyProvidedFieldsAndReturnsNewUpdatedAt() {
        LlmConfigView.GlobalConfigView saved =
                facade.updateGlobal(
                        new LlmConfigFacade.LlmGlobalUpdate(
                                null, null, 5000L, null, null, null, null));

        // 合并语义：预算改 5000，其余字段保持种子值
        assertThat(saved.dailyTokenBudgetPerUser()).isEqualTo(5000L);
        assertThat(saved.timeoutSeconds()).isEqualTo(30L);
        assertThat(saved.retry()).isEqualTo(1);
        assertThat(saved.updatedAt()).isEqualTo(NOW.toString());

        // 消费点视图即热生效：ConfigCenter 快照已换新
        assertThat(configCenter.llmGlobal().orElseThrow().dailyTokenBudgetPerUser())
                .isEqualTo(5000L);
    }

    @Test
    void updateGlobal_staleExpectedUpdatedAt_throwsConflict() {
        String wrongUpdatedAt = "2026-09-21T00:00:00Z";

        assertThatThrownBy(
                        () ->
                                facade.updateGlobal(
                                        new LlmConfigFacade.LlmGlobalUpdate(
                                                null,
                                                null,
                                                5000L,
                                                null,
                                                null,
                                                null,
                                                wrongUpdatedAt)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFIG_CONFLICT));

        // 原值不动
        assertThat(facade.view().global().dailyTokenBudgetPerUser()).isEqualTo(20000L);
    }

    @Test
    void updateProvider_promoteDefaultDemotesOthers_exactlyOneDefault() {
        LlmConfigView.ProviderConfigView saved =
                facade.updateProvider(
                        "glm",
                        new LlmConfigFacade.LlmProviderUpdate(
                                null, null, true, null, null, null, null, null));

        assertThat(saved.isDefault()).isTrue();
        LlmConfigView view = facade.view();
        assertThat(providerOf(view, "glm").isDefault()).isTrue();
        assertThat(providerOf(view, "deepseek").isDefault()).isFalse();
        assertThat(
                        view.providers().stream()
                                .filter(LlmConfigView.ProviderConfigView::isDefault)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void updateProvider_unknownProvider_throws404Code() {
        assertThatThrownBy(
                        () ->
                                facade.updateProvider(
                                        "nonexistent",
                                        new LlmConfigFacade.LlmProviderUpdate(
                                                "m", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.LLM_PROVIDER_NOT_FOUND));
    }

    @Test
    void updateProvider_fallbackToDisabledProvider_rejected() {
        // DEFECT-3 复现 a（修前红）：fallback 指向停用 provider（qwen 种子 enabled=false）须 2001 拒绝，
        // 静默接受会让 fallback 链失去灾备保护（运行时网关按 enabled 跳过，用户无感知）
        assertThatThrownBy(
                        () ->
                                facade.updateProvider(
                                        "deepseek",
                                        new LlmConfigFacade.LlmProviderUpdate(
                                                null, null, null, "qwen", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("fallback")
                .hasMessageContaining("qwen");

        // 原值不动（失败不落库）
        assertThat(providerOf(facade.view(), "deepseek").fallback()).isEqualTo("glm");
    }

    @Test
    void updateProvider_disableReferencedAsFallback_rejected() {
        // DEFECT-3 复现 b（修前红）：停用被启用中 provider（glm.fallback=deepseek）引用的 deepseek
        // 须拒绝并点名引用方（单 PUT 无法原子改两条记录 → 提示先解除引用的合理闭环）
        assertThatThrownBy(
                        () ->
                                facade.updateProvider(
                                        "deepseek",
                                        new LlmConfigFacade.LlmProviderUpdate(
                                                null, false, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("enabled")
                .hasMessageContaining("glm");

        // 原值不动：deepseek 仍启用
        assertThat(providerOf(facade.view(), "deepseek").enabled()).isTrue();
    }

    @Test
    void updateProvider_disableUnreferencedProvider_accepted() {
        // 闭环出口：无人引用的 provider（qwen 仅自持 fallback=deepseek，休眠引用）可正常启停
        facade.updateProvider(
                "qwen",
                new LlmConfigFacade.LlmProviderUpdate(
                        null, true, null, null, null, null, null, null));
        assertThat(providerOf(facade.view(), "qwen").enabled()).isTrue();

        facade.updateProvider(
                "qwen",
                new LlmConfigFacade.LlmProviderUpdate(
                        null, false, null, null, null, null, null, null));
        assertThat(providerOf(facade.view(), "qwen").enabled()).isFalse();
    }

    @Test
    void updateProvider_negativePrice_rejectedWithFieldReason() {
        assertThatThrownBy(
                        () ->
                                facade.updateProvider(
                                        "deepseek",
                                        new LlmConfigFacade.LlmProviderUpdate(
                                                null, null, null, null, -0.5, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("inputPricePerMillion")
                .hasMessageContaining("不能为负");

        // 原值不变（失败不落库）：deepseek 内置缺省单价 1.0（ADR-0020 迁移值）
        assertThat(
                        facade.view().providers().stream()
                                .filter(p -> p.name().equals("deepseek"))
                                .findFirst()
                                .orElseThrow()
                                .inputPricePerMillion())
                .isEqualTo(1.0);
    }

    @Test
    void writeApiKey_encryptsAtRest_returnsMaskedState_neverEchoesPlaintext() throws Exception {
        String plaintext = "sk-live-rotation-9876";

        LlmConfigView.ProviderConfigView saved =
                facade.writeApiKey("deepseek", new LlmConfigFacade.LlmApiKeyWrite(plaintext, null));

        // 回显仅脱敏态（DB 来源 + 尾 4 位），永不出现明文
        assertThat(saved.apiKey().status()).isEqualTo(LlmConfigView.ApiKeyView.STATUS_CONFIGURED);
        assertThat(saved.apiKey().source()).isEqualTo("DB");
        assertThat(saved.apiKey().last4()).isEqualTo("9876");

        // 落库为密文（明文不出现在任何存储文本中），且可解密往返
        String stored = repository.rows.get("llm.provider.deepseek").getConfigValue();
        assertThat(stored).doesNotContain(plaintext);
        String cipherText = objectMapper.readTree(stored).path("apiKeyCipher").asText();
        assertThat(cipherText).isNotEqualTo(plaintext);
        assertThat(new ConfigSecretCipher(SECRET).decrypt(cipherText)).isEqualTo(plaintext);
    }

    @Test
    void writeApiKey_configSecretMissing_throws30064AndNothingWritten() throws Exception {
        withEnvKey(
                "deepseek",
                "sk-env-test-key",
                () -> {
                    LlmConfigFacadeImpl degradedFacade =
                            new LlmConfigFacadeImpl(
                                    configService,
                                    configCenter,
                                    new ConfigSecretCipher(""), // CONFIG_SECRET 未配置 → 降级态
                                    List.of(deepseekAdapter),
                                    callLog,
                                    costGuard,
                                    executor,
                                    objectMapper);

                    assertThatThrownBy(
                                    () ->
                                            degradedFacade.writeApiKey(
                                                    "deepseek",
                                                    new LlmConfigFacade.LlmApiKeyWrite(
                                                            "sk-any", null)))
                            .isInstanceOf(BusinessException.class)
                            .satisfies(
                                    e -> {
                                        assertThat(((BusinessException) e).getErrorCode())
                                                .isEqualTo(ErrorCode.API_KEY_WRITE_DISABLED);
                                        assertThat(
                                                        ((BusinessException) e)
                                                                .getErrorCode()
                                                                .getHttpStatus())
                                                .isEqualTo(503);
                                    });

                    // 读不受降级影响，且未写入任何密文字段
                    assertThat(degradedFacade.view().apiKeyWriteEnabled()).isFalse();
                    assertThat(
                                    degradedFacade.view().providers().stream()
                                            .filter(p -> p.name().equals("deepseek"))
                                            .findFirst()
                                            .orElseThrow()
                                            .apiKey()
                                            .source())
                            .isEqualTo("ENV");
                    assertThat(
                                    objectMapper
                                            .readTree(
                                                    repository
                                                            .rows
                                                            .get("llm.provider.deepseek")
                                                            .getConfigValue())
                                            .has("apiKeyCipher"))
                            .isFalse();
                });
    }

    @Test
    void writeApiKey_blankKey_rejected() {
        assertThatThrownBy(
                        () ->
                                facade.writeApiKey(
                                        "deepseek", new LlmConfigFacade.LlmApiKeyWrite("  ", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void writeApiKey_dbKeyTakesPriorityOverEnvInRuntimeResolution() {
        withEnvKey(
                "deepseek",
                "sk-env-test-key",
                () -> {
                    // Arrange：deepseek 有 ENV key（无 yml 段下经 System.getenv 解析）
                    assertThat(configCenter.provider("deepseek").orElseThrow().apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.ENV);

                    // Act：页面写入新 key
                    facade.writeApiKey(
                            "deepseek",
                            new LlmConfigFacade.LlmApiKeyWrite("sk-db-priority-key", null));

                    // Assert：消费点解析 DB 优先（ADR-0018），adapter 下一次调用即用新 key
                    RuntimeLlmProvider resolved = configCenter.provider("deepseek").orElseThrow();
                    assertThat(resolved.apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.DB);
                    assertThat(resolved.apiKey()).isEqualTo("sk-db-priority-key");
                });
    }

    @Test
    void connectivityTest_success_returnsLatencyAndLogsTestScene() {
        when(deepseekAdapter.chat(any()))
                .thenReturn(
                        new LlmResponse(
                                "pong",
                                new LlmUsage(5, 2),
                                LlmProvider.DEEPSEEK,
                                "deepseek-flash"));

        LlmConnectivityResult result = facade.connectivityTest("deepseek");

        assertThat(result.ok()).isTrue();
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.latencyMillis()).isNotNull();
        // 请求用当前运行时模型，纯文本探活（不带 response_format）
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(deepseekAdapter).chat(req.capture());
        assertThat(req.getValue().model()).isEqualTo("deepseek-flash");
        assertThat(req.getValue().responseFormatType()).isNull();
        assertThat(req.getValue().briefTypeKey()).isEqualTo("test");
        // scene_key=test 落留痕（SUCCESS，成本按调用时点单价落死值）
        ArgumentCaptor<com.info.platform.domain.ai.LlmCallLog> entry =
                ArgumentCaptor.forClass(com.info.platform.domain.ai.LlmCallLog.class);
        verify(callLog).record(entry.capture());
        assertThat(entry.getValue().getSceneKey()).isEqualTo("test");
        // deepseek 内置缺省单价 1.0/4.0（ADR-0020 迁移值）：5×1 + 2×4 = 13 微元，按调用时点落死值
        assertThat(entry.getValue().getCostMicros()).isEqualTo(13L);
    }

    @Test
    void connectivityTest_adapterFailure_returnsOkFalseWithErrorAndLogs() {
        when(deepseekAdapter.chat(any())).thenThrow(new RuntimeException("401 Unauthorized"));

        LlmConnectivityResult result = facade.connectivityTest("deepseek");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("401");
        ArgumentCaptor<com.info.platform.domain.ai.LlmCallLog> entry =
                ArgumentCaptor.forClass(com.info.platform.domain.ai.LlmCallLog.class);
        verify(callLog).record(entry.capture());
        assertThat(entry.getValue().getErrorMessage()).contains("连通性测试失败");
    }

    @Test
    void connectivityTest_noAdapter_returnsOkFalse() {
        LlmConnectivityResult result = facade.connectivityTest("qwen");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("adapter");
    }

    @Test
    void connectivityTest_unknownProvider_throws404Code() {
        assertThatThrownBy(() -> facade.connectivityTest("nonexistent"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.LLM_PROVIDER_NOT_FOUND));
    }

    private static LlmConfigView.ProviderConfigView providerOf(LlmConfigView view, String name) {
        return view.providers().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
