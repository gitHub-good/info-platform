package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.infrastructure.ai.LlmDefaults;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * LLM 配置来源变更的启动链路集成测试（ADR-0020）：application.yml 已无 {@code llm:} 段， 代码内置缺省（{@link
 * LlmDefaults}）经种子链路保证全功能可用。
 *
 * <p>三条语义保证（共享上下文 + SQLite 内存库，ApplicationReadyEvent 已随上下文启动触发种子）： ① 全新库首启——llm.* 键由内置缺省播种、
 * 类型化视图/boot 级 baseUrl/页面基线全可用； ② DB 已有值——再次种子（重启模拟）不覆盖页面权威值（seed-if-absent）； ③ ENV key——无 yml 段下经
 * {@code System.getenv} 解析仍生效（ADR-0018 一等来源不动）。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmDefaultsBootstrapTest {

    @Autowired private ConfigCenter configCenter;

    @Autowired private RuntimeConfigService configService;

    @Test
    void freshBoot_seedsAllLlmKeysFromCodeDefaults_viewsFullyFunctional() {
        // yml 无 llm 段（ADR-0020）：本用例通过 = 键只能来自 LlmDefaults 种子
        assertThat(configService.read(ConfigCenter.KEY_LLM_GLOBAL))
                .map(RuntimeConfigEntry::document)
                .hasValueSatisfying(
                        doc -> {
                            assertThat(doc.path("timeoutSeconds").asLong()).isEqualTo(30L);
                            assertThat(doc.path("dailyTokenBudgetPerUser").asLong())
                                    .isEqualTo(20000L);
                            assertThat(doc.path("budgetWarnRatio").asDouble()).isEqualTo(0.8);
                            assertThat(doc.path("cacheTtlSeconds").path("brief-type-4").asLong())
                                    .isEqualTo(86400L);
                        });
        assertThat(configService.read(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + "deepseek"))
                .map(RuntimeConfigEntry::document)
                .hasValueSatisfying(
                        doc -> {
                            assertThat(doc.path("model").asText()).isEqualTo("deepseek-flash");
                            assertThat(doc.path("isDefault").asBoolean()).isTrue();
                            assertThat(doc.path("fallback").asText()).isEqualTo("glm");
                            assertThat(doc.path("inputPricePerMillion").asDouble()).isEqualTo(1.0);
                        });
        // 全部 4 provider 均有键（预留两家 enabled=false 照常可页面启用）
        for (String name : new String[] {"deepseek", "glm", "qwen", "kimi"}) {
            assertThat(configService.read(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name)).isPresent();
        }

        // 消费点视图可用：类型化全局、provider 视图（含价格）、boot 级 baseUrl（RESTART 级默认亦有值）
        assertThat(configCenter.llmGlobal()).isPresent();
        assertThat(configCenter.llmGlobal().orElseThrow().timeoutSeconds()).isEqualTo(30L);
        RuntimeLlmProvider deepseek = configCenter.provider("deepseek").orElseThrow();
        assertThat(deepseek.enabled()).isTrue();
        assertThat(deepseek.inputPricePerMillion()).isEqualTo(1.0);
        assertThat(configCenter.bootLlmProviderBaseUrl("deepseek"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(configCenter.bootLlmProviderBaseUrl("glm"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
    }

    @Test
    void existingDbValues_surviveReseed_pageStaysAuthoritative() {
        // Arrange：模拟页面已改值（日预算 54321），保留原文以便还原
        RuntimeConfigEntry before = configService.read(ConfigCenter.KEY_LLM_GLOBAL).orElseThrow();
        String originalJson = before.json();
        configService.write(
                ConfigCenter.KEY_LLM_GLOBAL,
                "{\"timeoutSeconds\":30,\"retry\":1,\"dailyTokenBudgetPerUser\":54321,"
                        + "\"budgetWarnRatio\":0.8,\"cacheDefaultTtlSeconds\":3600,"
                        + "\"cacheTtlSeconds\":{\"brief-type-1\":3600,\"brief-type-3\":3600,"
                        + "\"brief-type-4\":86400}}",
                null);
        try {
            // Act：重启模拟——再走一遍启动就绪（种子导入 + 冻结快照）
            configCenter.onApplicationReady();

            // Assert：DB 已有键不被内置缺省种子覆盖（seed-if-absent，页面值即权威）
            assertThat(
                            configService
                                    .read(ConfigCenter.KEY_LLM_GLOBAL)
                                    .orElseThrow()
                                    .document()
                                    .path("dailyTokenBudgetPerUser")
                                    .asLong())
                    .isEqualTo(54321L);
            assertThat(configCenter.llmGlobal().orElseThrow().dailyTokenBudgetPerUser())
                    .isEqualTo(54321L);
        } finally {
            // 还原共享上下文的 DB 行与快照，避免污染其他集成测试（rollback 不会自动回内存快照）
            configService.write(ConfigCenter.KEY_LLM_GLOBAL, originalJson, null);
        }
    }

    @Test
    void envApiKey_stillResolvedWithoutYmlSection() {
        // 无 yml llm 段：provider 文档无密文 → ENV 来源按 NAME_API_KEY 命名经 LlmDefaults.envApiKey 解析
        // （ADR-0018 一等来源不动；System 不可 mock，桩打在 ENV 读取唯一收敛点 LlmDefaults 上，其余静态真调）
        try (MockedStatic<LlmDefaults> defaults =
                mockStatic(LlmDefaults.class, CALLS_REAL_METHODS)) {
            defaults.when(() -> LlmDefaults.envApiKey("deepseek")).thenReturn("sk-env-it-key");

            RuntimeLlmProvider resolved = configCenter.provider("deepseek").orElseThrow();
            assertThat(resolved.apiKeySource()).isEqualTo(RuntimeLlmProvider.ApiKeySource.ENV);
            assertThat(resolved.apiKey()).isEqualTo("sk-env-it-key");
            assertThat(resolved.apiKeyLast4()).isEqualTo("-key");

            // 同一桩内撤销注入 → 回落 NONE（真实环境未设 DEEPSEEK_API_KEY 时的稳态）
            defaults.when(() -> LlmDefaults.envApiKey("deepseek")).thenReturn(null);
            assertThat(configCenter.provider("deepseek").orElseThrow().apiKeySource())
                    .isEqualTo(RuntimeLlmProvider.ApiKeySource.NONE);
        }
    }
}
