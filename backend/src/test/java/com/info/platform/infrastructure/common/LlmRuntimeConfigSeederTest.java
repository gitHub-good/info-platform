package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.infrastructure.ai.LlmDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LLM 域种子测试（T34；ADR-0020 起种子来源为 {@link LlmDefaults} 代码内置缺省）：派生 llm.global 与 llm.provider.* 种子
 * JSON（键空间形状对齐方案 §4.1，供 T35 消费端与校验器对齐契约）；api-key 不种子（环境变量一等来源，ADR-0018）。
 */
class LlmRuntimeConfigSeederTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void seeds_coverGlobalAndAllProviders_withKeySpaceShape() throws Exception {
        // Act
        List<RuntimeConfigSeed> seeds = new LlmRuntimeConfigSeeder(mapper).seeds();

        // Assert：1 全局 + 4 provider（内置缺省全集）
        assertThat(seeds).hasSize(5);
        assertThat(seeds)
                .extracting(RuntimeConfigSeed::configKey)
                .containsExactly(
                        "llm.global",
                        "llm.provider.deepseek",
                        "llm.provider.glm",
                        "llm.provider.qwen",
                        "llm.provider.kimi");

        // llm.global 键空间：超时/重试/预算/告警阈值/缓存 TTL（默认 + 分档）
        JsonNode global = mapper.readTree(seeds.get(0).json());
        assertThat(global.get("timeoutSeconds").asLong()).isEqualTo(30);
        assertThat(global.get("retry").asInt()).isEqualTo(1);
        assertThat(global.get("dailyTokenBudgetPerUser").asLong()).isEqualTo(20000);
        assertThat(global.get("budgetWarnRatio").asDouble()).isEqualTo(0.8);
        assertThat(global.get("cacheDefaultTtlSeconds").asLong()).isEqualTo(3600);
        assertThat(global.get("cacheTtlSeconds").get("brief-type-4").asLong()).isEqualTo(86400);
        assertThat(global.get("cacheTtlSeconds").get("brief-type-1").asLong()).isEqualTo(3600);

        // provider 键空间：model/enabled/isDefault/fallback/单价/baseUrl；api-key 不种子
        JsonNode deepseek = mapper.readTree(seeds.get(1).json());
        assertThat(deepseek.get("model").asText()).isEqualTo("deepseek-flash");
        assertThat(deepseek.get("enabled").asBoolean()).isTrue();
        assertThat(deepseek.get("isDefault").asBoolean()).isTrue();
        assertThat(deepseek.get("fallback").asText()).isEqualTo("glm");
        assertThat(deepseek.get("inputPricePerMillion").asDouble()).isEqualTo(1.0);
        assertThat(deepseek.get("outputPricePerMillion").asDouble()).isEqualTo(4.0);
        assertThat(deepseek.get("baseUrl").asText()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.has("apiKeyCipher")).isFalse();
        assertThat(deepseek.has("apiKeyLast4")).isFalse();

        // 预留 provider 照常种子（enabled=false 形态，页面可启用）
        JsonNode qwen = mapper.readTree(seeds.get(3).json());
        assertThat(qwen.get("enabled").asBoolean()).isFalse();
        assertThat(qwen.get("model").asText()).isEqualTo("qwen-plus");

        // 种子带人读说明
        assertThat(seeds.get(0).description()).contains("LLM");
    }

    @Test
    void seeds_areDeterministic_acrossSeederInstances() throws Exception {
        // 同一 JVM 多次构造种子器（含共享上下文重启模拟）产出字节级一致，seed-if-absent 不产生抖动
        List<RuntimeConfigSeed> first = new LlmRuntimeConfigSeeder(mapper).seeds();
        List<RuntimeConfigSeed> second = new LlmRuntimeConfigSeeder(new ObjectMapper()).seeds();
        assertThat(first)
                .extracting(RuntimeConfigSeed::json)
                .containsExactlyElementsOf(second.stream().map(RuntimeConfigSeed::json).toList());
    }
}
