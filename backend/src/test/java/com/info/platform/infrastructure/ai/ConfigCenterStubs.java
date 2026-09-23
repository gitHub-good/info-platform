package com.info.platform.infrastructure.ai;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeLlmGlobal;
import com.info.platform.infrastructure.common.RuntimeLlmProvider;
import com.info.platform.infrastructure.common.RuntimeLlmProvider.ApiKeySource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T35 测试夹具：把 {@link LlmProviderFixtures.ProviderFixture}（可变 provider 形状）桥接为 {@link ConfigCenter}
 * 运行时视图的 Mockito stub。
 *
 * <p>热改测试拨动点：{@code llmGlobal()} 读 {@link AtomicReference}（改预算/阈值/超时即换引用）、 {@code provider(name)}
 * 每次调用按 {@link Map} 当前值现算视图（改单价/启停即改 map 内夹具）、 {@code bootLlmProviderBaseUrl} 同源取 baseUrl（RESTART
 * 级在测试内恒定即可）。缺省全局视图取 {@link LlmDefaults} 内置值（ADR-0020）。
 */
final class ConfigCenterStubs {

    private ConfigCenterStubs() {}

    /** 内置缺省的全局视图（对齐 {@code LlmInfrastructureConfig#runtimeGlobal} 缺键回落）。 */
    static RuntimeLlmGlobal globalOfDefaults() {
        return new RuntimeLlmGlobal(
                LlmDefaults.TIMEOUT_SECONDS,
                LlmDefaults.RETRY,
                LlmDefaults.DAILY_TOKEN_BUDGET_PER_USER,
                LlmDefaults.BUDGET_WARN_RATIO,
                LlmDefaults.CACHE_DEFAULT_TTL_SECONDS,
                LlmDefaults.CACHE_TTL_SECONDS);
    }

    /** 便捷全局视图（预算/阈值/超时按需覆盖，其余缺省）。 */
    static RuntimeLlmGlobal global(long timeoutSeconds, long dailyBudget, double warnRatio) {
        return new RuntimeLlmGlobal(timeoutSeconds, 1, dailyBudget, warnRatio, 3600, Map.of());
    }

    /** provider 夹具 → 运行时视图（apiKey 按 ENV 来源解析，DB 来源由 Facade 写路径覆盖）。 */
    static RuntimeLlmProvider viewOf(LlmProviderFixtures.ProviderFixture provider) {
        boolean hasKey = provider.getApiKey() != null && !provider.getApiKey().isBlank();
        return new RuntimeLlmProvider(
                provider.getName(),
                provider.getModel(),
                provider.isEnabled(),
                provider.isDefault(),
                provider.getFallback(),
                provider.getInputPricePerMillion(),
                provider.getOutputPricePerMillion(),
                provider.getBaseUrl(),
                hasKey ? provider.getApiKey() : "",
                hasKey ? ApiKeySource.ENV : ApiKeySource.NONE,
                hasKey ? provider.getApiKey().substring(provider.getApiKey().length() - 4) : null);
    }

    /** 可变运行时状态：全局视图引用 + provider 夹具表（测试拨动预算/单价/启停）。 */
    static final class Runtime {
        final AtomicReference<RuntimeLlmGlobal> global;
        final Map<String, LlmProviderFixtures.ProviderFixture> providers = new HashMap<>();

        Runtime(LlmProviderFixtures.ProviderFixture... providers) {
            this.global = new AtomicReference<>(globalOfDefaults());
            for (LlmProviderFixtures.ProviderFixture provider : providers) {
                this.providers.put(provider.getName(), provider);
            }
        }

        void setBudget(long dailyBudget) {
            RuntimeLlmGlobal current = global.get();
            global.set(
                    new RuntimeLlmGlobal(
                            current.timeoutSeconds(),
                            current.retry(),
                            dailyBudget,
                            current.budgetWarnRatio(),
                            current.cacheDefaultTtlSeconds(),
                            current.cacheTtlSeconds()));
        }

        void setTimeoutSeconds(long timeoutSeconds) {
            RuntimeLlmGlobal current = global.get();
            global.set(
                    new RuntimeLlmGlobal(
                            timeoutSeconds,
                            current.retry(),
                            current.dailyTokenBudgetPerUser(),
                            current.budgetWarnRatio(),
                            current.cacheDefaultTtlSeconds(),
                            current.cacheTtlSeconds()));
        }
    }

    /** ConfigCenter stub：三读方法全部按 {@link Runtime} 当前值现算（用时读取语义）。 */
    static ConfigCenter stub(Runtime runtime) {
        ConfigCenter configCenter = mock(ConfigCenter.class);
        when(configCenter.llmGlobal()).thenAnswer(inv -> Optional.of(runtime.global.get()));
        when(configCenter.provider(anyString()))
                .thenAnswer(
                        inv -> {
                            LlmProviderFixtures.ProviderFixture provider =
                                    runtime.providers.get(inv.getArgument(0, String.class));
                            return provider == null
                                    ? Optional.empty()
                                    : Optional.of(viewOf(provider));
                        });
        when(configCenter.bootLlmProviderBaseUrl(anyString()))
                .thenAnswer(
                        inv -> {
                            LlmProviderFixtures.ProviderFixture provider =
                                    runtime.providers.get(inv.getArgument(0, String.class));
                            return provider == null ? null : provider.getBaseUrl();
                        });
        return configCenter;
    }

    /** 固定单 provider stub（adapter 单测：无热改诉求）。 */
    static ConfigCenter stubOf(LlmProviderFixtures.ProviderFixture... providers) {
        return stub(new Runtime(providers));
    }
}
