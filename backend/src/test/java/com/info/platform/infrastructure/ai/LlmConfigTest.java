package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.infrastructure.ai.LlmConfig.Cache;
import com.info.platform.infrastructure.ai.LlmConfig.Provider;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * LlmConfig 单测（T19）：配置助手方法 + 绑定（含 {@code default} 保留字键 → {@code isDefault} 字段）。
 *
 * <p>覆盖 {@link LlmConfig#defaultProviderName}/{@link LlmConfig#providerByName}/{@link
 * LlmConfig#fallbackChain}（含环检测）与 {@link LlmConfig.Cache#ttlFor}；并用 {@link Binder} + {@link
 * MapConfigurationPropertySource} 验证 {@code llm.*} 绑定（重点验证 yaml {@code default: true} 经 relaxed
 * binding 绑到 {@code Provider.isDefault}）。
 */
class LlmConfigTest {

    @Test
    void defaultProviderName_returnsDefaultProvider() {
        LlmConfig config = configWith(deepseekProvider(), glmProvider());
        assertThat(config.defaultProviderName()).isEqualTo("deepseek");
    }

    @Test
    void providerByName_returnsConfigOrNull() {
        LlmConfig config = configWith(deepseekProvider(), glmProvider());
        assertThat(config.providerByName("glm").getModel()).isEqualTo("glm-4-flash-250414");
        assertThat(config.providerByName("qwen")).isNull();
    }

    @Test
    void fallbackChain_followsFallbackAndStopsOnCycle() {
        // deepseek(fallback=glm) -> glm(fallback=deepseek,已访问止) = [deepseek, glm]
        LlmConfig config = configWith(deepseekProvider(), glmProvider());
        assertThat(config.fallbackChain()).containsExactly("deepseek", "glm");
    }

    @Test
    void fallbackChain_emptyWhenNoDefault() {
        Provider glm = glmProvider();
        glm.setDefault(false);
        LlmConfig config = configWith(glm);
        assertThat(config.fallbackChain()).isEmpty();
    }

    @Test
    void cacheTtlFor_resolvesByBriefTypeOrDefault() {
        Cache cache = new Cache();
        cache.setDefaultTtlSeconds(600);
        cache.getTtl().put("brief-type-1", 3600L);
        cache.getTtl().put("brief-type-4", 86400L);

        assertThat(cache.ttlFor("1")).isEqualTo(Duration.ofSeconds(3600));
        assertThat(cache.ttlFor("4")).isEqualTo(Duration.ofSeconds(86400));
        assertThat(cache.ttlFor("9")).isEqualTo(Duration.ofSeconds(600)); // 未配置→默认
    }

    @Test
    void timeout_defaultsTo30s() {
        assertThat(new LlmConfig().timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void dailyTokenBudget_defaultsTo20000() {
        assertThat(new LlmConfig().getDailyTokenBudgetPerUser()).isEqualTo(20000L);
    }

    @Test
    void budgetWarnRatio_defaultsTo080() {
        assertThat(new LlmConfig().getBudgetWarnRatio()).isEqualTo(0.8);
    }

    @Test
    void providerPrice_defaultsToZero_andBindsFromYaml() {
        // Assert：未配置单价默认 0（免费档/未确认厂商不估算）
        assertThat(new Provider().getInputPricePerMillion()).isZero();
        assertThat(new Provider().getOutputPricePerMillion()).isZero();

        // Act：Binder 验证 yaml 单价键绑定（与 @ConfigurationProperties 同规则）
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("llm.providers[0].name", "deepseek");
        source.put("llm.providers[0].input-price-per-million", "1.0");
        source.put("llm.providers[0].output-price-per-million", "4.0");
        source.put("llm.budget-warn-ratio", "0.9");
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        LlmConfig config = binder.bind("llm", Bindable.of(LlmConfig.class)).orElse(new LlmConfig());

        // Assert
        assertThat(config.providerByName("deepseek").getInputPricePerMillion()).isEqualTo(1.0);
        assertThat(config.providerByName("deepseek").getOutputPricePerMillion()).isEqualTo(4.0);
        assertThat(config.getBudgetWarnRatio()).isEqualTo(0.9);
    }

    @Test
    void binding_mapsYamlIncludingDefaultKey() {
        // 验证 yaml `default: true` 经 relaxed binding 绑到 Provider.isDefault（Binder 走与
        // @ConfigurationProperties 相同的绑定规则，免启完整 Spring 上下文）
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("llm.providers[0].name", "deepseek");
        source.put("llm.providers[0].base-url", "https://api.deepseek.com");
        source.put("llm.providers[0].model", "deepseek-flash");
        source.put("llm.providers[0].api-key", "");
        source.put("llm.providers[0].enabled", "true");
        source.put("llm.providers[0].default", "true");
        source.put("llm.providers[0].fallback", "glm");
        source.put("llm.providers[1].name", "glm");
        source.put("llm.providers[1].base-url", "https://open.bigmodel.cn/api/paas/v4");
        source.put("llm.providers[1].model", "glm-4-flash-250414");
        source.put("llm.providers[1].enabled", "true");
        source.put("llm.providers[1].default", "false");
        source.put("llm.providers[1].fallback", "deepseek");
        source.put("llm.timeout-seconds", "30");
        source.put("llm.daily-token-budget-per-user", "20000");
        source.put("llm.cache.default-ttl-seconds", "3600");
        source.put("llm.cache.maximum-size", "1000");
        source.put("llm.cache.ttl.brief-type-1", "3600");
        source.put("llm.cache.ttl.brief-type-4", "86400");

        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        LlmConfig config = binder.bind("llm", Bindable.of(LlmConfig.class)).orElse(new LlmConfig());

        assertThat(config.defaultProviderName()).isEqualTo("deepseek");
        assertThat(config.fallbackChain()).containsExactly("deepseek", "glm");
        assertThat(config.providerByName("deepseek").isDefault()).isTrue();
        assertThat(config.providerByName("glm").isDefault()).isFalse();
        assertThat(config.providerByName("deepseek").getApiKey()).isEmpty();
        assertThat(config.getDailyTokenBudgetPerUser()).isEqualTo(20000L);
        assertThat(config.getCache().ttlFor("4")).isEqualTo(Duration.ofSeconds(86400));
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    // ---- 共享测试夹具（同包其它 LLM 测试复用）----

    static LlmConfig configWith(Provider... providers) {
        LlmConfig config = new LlmConfig();
        config.setProviders(new java.util.ArrayList<>(Arrays.asList(providers)));
        return config;
    }

    static Provider deepseekProvider() {
        Provider p = new Provider();
        p.setName("deepseek");
        p.setBaseUrl("https://api.deepseek.com");
        p.setModel("deepseek-flash");
        p.setApiKey("test-key");
        p.setEnabled(true);
        p.setDefault(true);
        p.setFallback("glm");
        return p;
    }

    static Provider glmProvider() {
        Provider p = new Provider();
        p.setName("glm");
        p.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        p.setModel("glm-4-flash-250414");
        p.setApiKey("test-glm-key");
        p.setEnabled(true);
        p.setDefault(false);
        p.setFallback("deepseek");
        return p;
    }
}
