package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.infrastructure.ai.LlmConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LLM 域种子测试（T34）：从 LlmConfig 派生 llm.global 与 llm.provider.* 种子 JSON（键空间形状对齐方案 §4.1， 供 T35
 * 消费端与校验器对齐契约）；api-key 不种子（环境变量一等来源，ADR-0018）。
 */
class LlmRuntimeConfigSeederTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LlmConfig twoProviders() {
        LlmConfig config = new LlmConfig();
        config.setTimeoutSeconds(30);
        config.setRetry(1);
        config.setDailyTokenBudgetPerUser(20000);
        config.setBudgetWarnRatio(0.8);
        config.getCache().setDefaultTtlSeconds(3600);
        config.getCache().setTtl(java.util.Map.of("brief-type-1", 3600L, "brief-type-4", 86400L));

        LlmConfig.Provider deepseek = new LlmConfig.Provider();
        deepseek.setName("deepseek");
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setModel("deepseek-flash");
        deepseek.setApiKey("sk-env-secret");
        deepseek.setEnabled(true);
        deepseek.setDefault(true);
        deepseek.setFallback("glm");
        deepseek.setInputPricePerMillion(1.0);
        deepseek.setOutputPricePerMillion(4.0);

        LlmConfig.Provider glm = new LlmConfig.Provider();
        glm.setName("glm");
        glm.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        glm.setModel("glm-4-flash-250414");
        glm.setEnabled(true);
        glm.setDefault(false);
        glm.setFallback("deepseek");

        config.setProviders(List.of(deepseek, glm));
        return config;
    }

    @Test
    void seeds_coverGlobalAndProviders_withKeySpaceShape() throws Exception {
        // Act
        List<RuntimeConfigSeed> seeds = new LlmRuntimeConfigSeeder(twoProviders(), mapper).seeds();

        // Assert：1 全局 + 2 provider
        assertThat(seeds).hasSize(3);
        assertThat(seeds)
                .extracting(RuntimeConfigSeed::configKey)
                .containsExactly("llm.global", "llm.provider.deepseek", "llm.provider.glm");

        // llm.global 键空间：超时/重试/预算/告警阈值/缓存 TTL（默认 + 分档）
        JsonNode global = mapper.readTree(seeds.get(0).json());
        assertThat(global.get("timeoutSeconds").asLong()).isEqualTo(30);
        assertThat(global.get("retry").asInt()).isEqualTo(1);
        assertThat(global.get("dailyTokenBudgetPerUser").asLong()).isEqualTo(20000);
        assertThat(global.get("budgetWarnRatio").asDouble()).isEqualTo(0.8);
        assertThat(global.get("cacheDefaultTtlSeconds").asLong()).isEqualTo(3600);
        assertThat(global.get("cacheTtlSeconds").get("brief-type-4").asLong()).isEqualTo(86400);

        // provider 键空间：model/enabled/isDefault/fallback/单价/baseUrl；api-key 不种子
        JsonNode provider = mapper.readTree(seeds.get(1).json());
        assertThat(provider.get("model").asText()).isEqualTo("deepseek-flash");
        assertThat(provider.get("enabled").asBoolean()).isTrue();
        assertThat(provider.get("isDefault").asBoolean()).isTrue();
        assertThat(provider.get("fallback").asText()).isEqualTo("glm");
        assertThat(provider.get("inputPricePerMillion").asDouble()).isEqualTo(1.0);
        assertThat(provider.get("baseUrl").asText()).isEqualTo("https://api.deepseek.com");
        assertThat(provider.has("apiKeyCipher")).isFalse();
        assertThat(provider.has("apiKeyLast4")).isFalse();

        // 种子带人读说明
        assertThat(seeds.get(0).description()).contains("LLM");
    }

    @Test
    void seeds_emptyProviders_stillSeedsGlobal() {
        List<RuntimeConfigSeed> seeds = new LlmRuntimeConfigSeeder(new LlmConfig(), mapper).seeds();

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).configKey()).isEqualTo("llm.global");
    }
}
