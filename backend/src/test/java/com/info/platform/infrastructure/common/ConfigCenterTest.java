package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.infrastructure.ai.LlmDefaults;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link ConfigCenter} 测试（T34；ADR-0020 起 LLM 域回落为 {@code LlmDefaults} 内置缺省、ENV key 直读 {@code
 * System.getenv}）：启动种子导入与启动期冻结快照（bootValue）、类型化视图解析、API key 解析优先级 DB&gt;ENV&gt;空（ADR-0018）、密文解密失败回落
 * ENV。
 */
class ConfigCenterTest {

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");
    private static final String SECRET = "test-config-secret-0123456789abcdef";

    /** 内存仓储（真实 upsert 语义）。 */
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
    private InMemoryRepository repository;
    private RuntimeConfigService service;
    private ConfigSecretCipher cipher;
    private ConfigCenter configCenter;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        service =
                new RuntimeConfigService(
                        repository,
                        List.of(),
                        (event) -> {},
                        Clock.fixed(T1, ZoneOffset.UTC),
                        objectMapper);
        cipher = new ConfigSecretCipher(SECRET);
        configCenter = new ConfigCenter(service, List.of(), cipher, objectMapper);
    }

    private void store(String key, String json) {
        repository.save(RuntimeConfig.create(key, json, null, T1));
        service.reload();
    }

    /** ENV key 注入桩：stub {@link LlmDefaults#envApiKey}（全部 ENV 读取的唯一收敛点；System 不可 mock），其余静态真调。 */
    private static void withEnvKey(String providerName, String value, Runnable body) {
        try (MockedStatic<LlmDefaults> defaults =
                mockStatic(LlmDefaults.class, CALLS_REAL_METHODS)) {
            defaults.when(() -> LlmDefaults.envApiKey(providerName)).thenReturn(value);
            body.run();
        }
    }

    @Test
    void onApplicationReady_seedsAllDomains_andFreezesBootSnapshot() {
        // Arrange：两个种子源（LLM 全局 + 聚合）
        RuntimeConfigSeeder llmSeeder =
                () ->
                        List.of(
                                new RuntimeConfigSeed(
                                        "llm.global", "{\"timeoutSeconds\":30}", "LLM 全局参数"));
        RuntimeConfigSeeder aggregationSeeder =
                () ->
                        List.of(
                                new RuntimeConfigSeed(
                                        "aggregation.global",
                                        "{\"detailTimeoutMillis\":2000}",
                                        "聚合总超时"));
        configCenter =
                new ConfigCenter(
                        service, List.of(llmSeeder, aggregationSeeder), cipher, objectMapper);

        // Act：模拟 ApplicationReadyEvent
        configCenter.onApplicationReady();

        // Assert：种子全部落库 + 快照可见
        assertThat(repository.rows).containsKeys("llm.global", "aggregation.global");
        assertThat(configCenter.document("llm.global")).isPresent();
        // 再次就绪（重启模拟）不覆盖 DB 权威值
        repository.rows.put(
                "llm.global",
                RuntimeConfig.create("llm.global", "{\"timeoutSeconds\":99}", null, T1));
        service.reload();
        configCenter.onApplicationReady();
        assertThat(configCenter.document("llm.global").orElseThrow().get("timeoutSeconds").asLong())
                .isEqualTo(99);
    }

    @Test
    void bootDocument_frozenAtBoot_currentDocumentFollowsWrite() {
        // Arrange：启动时值为 2000，冻结启动快照
        store("aggregation.global", "{\"detailTimeoutMillis\":2000}");
        configCenter.onApplicationReady();

        // Act：页面写新值（当前快照更新）
        store("aggregation.global", "{\"detailTimeoutMillis\":8000}");

        // Assert：RESTART 级读取（bootDocument）仍为启动值；LIVE 级读取（document）为新值
        assertThat(
                        configCenter
                                .bootDocument("aggregation.global")
                                .orElseThrow()
                                .get("detailTimeoutMillis")
                                .asLong())
                .isEqualTo(2000);
        assertThat(
                        configCenter
                                .document("aggregation.global")
                                .orElseThrow()
                                .get("detailTimeoutMillis")
                                .asLong())
                .isEqualTo(8000);
    }

    @Test
    void llmGlobal_parsesTypedView_withTtlFallback() {
        // Arrange
        store(
                ConfigCenter.KEY_LLM_GLOBAL,
                "{\"timeoutSeconds\":30,\"retry\":1,\"dailyTokenBudgetPerUser\":20000,"
                        + "\"budgetWarnRatio\":0.8,\"cacheDefaultTtlSeconds\":3600,"
                        + "\"cacheTtlSeconds\":{\"brief-type-1\":600}}");

        // Act
        var global = configCenter.llmGlobal();

        // Assert：字段解析 + 分档 TTL 与默认回落
        assertThat(global).isPresent();
        assertThat(global.orElseThrow().timeoutSeconds()).isEqualTo(30);
        assertThat(global.orElseThrow().dailyTokenBudgetPerUser()).isEqualTo(20000);
        assertThat(global.orElseThrow().timeout().toSeconds()).isEqualTo(30);
        assertThat(global.orElseThrow().ttlFor("1").toSeconds()).isEqualTo(600);
        assertThat(global.orElseThrow().ttlFor("4").toSeconds()).isEqualTo(3600);
    }

    @Test
    void llmGlobal_absentKey_returnsEmpty() {
        assertThat(configCenter.llmGlobal()).isEmpty();
    }

    @Test
    void provider_dbCipherBeatsEnv() {
        // Arrange：DB 已写入密文 + 尾 4 位；环境变量同 provider 也有 key（DB 优先）
        store(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                "{\"model\":\"deepseek-flash\",\"enabled\":true,\"isDefault\":true,\"fallback\":\"glm\","
                        + "\"inputPricePerMillion\":1.0,\"outputPricePerMillion\":4.0,"
                        + "\"baseUrl\":\"https://api.deepseek.com\","
                        + "\"apiKeyCipher\":\""
                        + cipher.encrypt("sk-db-secret")
                        + "\",\"apiKeyLast4\":\"cret\"}");

        // Act + Assert
        withEnvKey(
                "deepseek",
                "sk-env-9999",
                () -> {
                    var provider = configCenter.provider("deepseek");
                    assertThat(provider).isPresent();
                    assertThat(provider.orElseThrow().apiKey()).isEqualTo("sk-db-secret");
                    assertThat(provider.orElseThrow().apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.DB);
                    assertThat(provider.orElseThrow().apiKeyLast4()).isEqualTo("cret");
                    assertThat(provider.orElseThrow().model()).isEqualTo("deepseek-flash");
                });
    }

    @Test
    void provider_envFallbackWhenNoCipher_andNoneWhenNoSource() {
        // Arrange：deepseek 有 ENV key 无密文；glm 两者皆无
        store(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                "{\"model\":\"deepseek-flash\",\"enabled\":true,\"isDefault\":true,"
                        + "\"inputPricePerMillion\":1,\"outputPricePerMillion\":4,"
                        + "\"baseUrl\":\"https://api.deepseek.com\"}");
        store(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "glm",
                "{\"model\":\"glm-4-flash\",\"enabled\":true,\"baseUrl\":\"https://open.bigmodel.cn\"}");

        // Act + Assert：deepseek 回落 ENV（尾 4 位来自 env 明文）；glm 无来源
        withEnvKey(
                "deepseek",
                "sk-env-9999",
                () -> {
                    var deepseek = configCenter.provider("deepseek").orElseThrow();
                    assertThat(deepseek.apiKey()).isEqualTo("sk-env-9999");
                    assertThat(deepseek.apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.ENV);
                    assertThat(deepseek.apiKeyLast4()).isEqualTo("9999");

                    var glm = configCenter.provider("glm").orElseThrow();
                    assertThat(glm.apiKey()).isEmpty();
                    assertThat(glm.apiKeySource()).isEqualTo(RuntimeLlmProvider.ApiKeySource.NONE);
                    assertThat(glm.hasApiKey()).isFalse();
                });
    }

    @Test
    void provider_undecryptableCipher_fallsBackToEnvWithDegrade() {
        // Arrange：密文由另一主密钥加密（模拟 CONFIG_SECRET 轮换）；ENV 有 key
        ConfigSecretCipher rotated = new ConfigSecretCipher("rotated-config-secret-0123456789");
        store(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek",
                "{\"model\":\"deepseek-flash\",\"enabled\":true,\"baseUrl\":\"https://api.deepseek.com\","
                        + "\"apiKeyCipher\":\""
                        + rotated.encrypt("sk-old-key")
                        + "\"}");

        // Act + Assert：解密失败不阻断读，回落 ENV（ADR-0018「不禁读」）
        withEnvKey(
                "deepseek",
                "sk-env-9999",
                () -> {
                    var provider = configCenter.provider("deepseek").orElseThrow();
                    assertThat(provider.apiKey()).isEqualTo("sk-env-9999");
                    assertThat(provider.apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.ENV);
                });
    }

    @Test
    void provider_unknownName_andDefaultsFallback() {
        // Arrange：qwen 无运行时键（模拟种子前读取）；ENV 注入 QWEN_API_KEY（无 yml 段下直读环境变量，ADR-0018）
        withEnvKey(
                "qwen",
                "sk-qwen-env",
                () -> {
                    // Act + Assert：未知名 → 空；内置缺省条目 → 从 LlmDefaults 兜底（ENV key + last4）
                    assertThat(configCenter.provider("nonexistent")).isEmpty();
                    var provider = configCenter.provider("qwen").orElseThrow();
                    assertThat(provider.model()).isEqualTo("qwen-plus");
                    assertThat(provider.baseUrl())
                            .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
                    assertThat(provider.enabled()).isFalse();
                    assertThat(provider.apiKeySource())
                            .isEqualTo(RuntimeLlmProvider.ApiKeySource.ENV);
                    assertThat(provider.apiKeyLast4()).isEqualTo("-env");
                });
    }

    @Test
    void bootLlmProviderBaseUrl_bootSnapshotFirst_defaultsFallback() {
        // Arrange：启动冻结快照含 glm 键；qwen 无键回落内置缺省；未知名 null
        store(
                ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "glm",
                "{\"model\":\"glm-4-flash-250414\",\"baseUrl\":\"https://glm.example.custom\"}");
        configCenter.onApplicationReady();

        // Act + Assert
        assertThat(configCenter.bootLlmProviderBaseUrl("glm"))
                .isEqualTo("https://glm.example.custom");
        assertThat(configCenter.bootLlmProviderBaseUrl("qwen"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(configCenter.bootLlmProviderBaseUrl("nonexistent")).isNull();
    }
}
