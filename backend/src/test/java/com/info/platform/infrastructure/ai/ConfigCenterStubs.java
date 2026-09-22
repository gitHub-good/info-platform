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
 * T35 测试夹具：把 {@link LlmConfig}（yml 形状）桥接为 {@link ConfigCenter} 运行时视图的 Mockito stub。
 *
 * <p>热改测试拨动点：{@code llmGlobal()} 读 {@link AtomicReference}（改预算/阈值/超时即换引用）、 {@code provider(name)}
 * 每次调用按 {@link Map} 当前值现算视图（改单价/启停即改 map 内 POJO）、 {@code bootLlmProviderBaseUrl} 同源取
 * baseUrl（RESTART 级在测试内恒定即可）。
 */
final class ConfigCenterStubs {

    private ConfigCenterStubs() {}

    /** 由 yml 形状配置构造运行时全局视图。 */
    static RuntimeLlmGlobal globalOf(LlmConfig config) {
        return new RuntimeLlmGlobal(
                config.getTimeoutSeconds(),
                config.getRetry(),
                config.getDailyTokenBudgetPerUser(),
                config.getBudgetWarnRatio(),
                config.getCache().getDefaultTtlSeconds(),
                config.getCache().getTtl());
    }

    /** 便捷全局视图（预算/阈值/超时按需覆盖，其余缺省）。 */
    static RuntimeLlmGlobal global(long timeoutSeconds, long dailyBudget, double warnRatio) {
        return new RuntimeLlmGlobal(timeoutSeconds, 1, dailyBudget, warnRatio, 3600, Map.of());
    }

    /** yml provider POJO → 运行时视图（apiKey 按 ENV 来源解析，DB 来源由 Facade 写路径覆盖）。 */
    static RuntimeLlmProvider viewOf(LlmConfig.Provider provider) {
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

    /** 可变运行时状态：全局视图引用 + provider POJO 表（测试拨动预算/单价/启停）。 */
    static final class Runtime {
        final AtomicReference<RuntimeLlmGlobal> global;
        final Map<String, LlmConfig.Provider> providers = new HashMap<>();

        Runtime(LlmConfig config) {
            this.global = new AtomicReference<>(globalOf(config));
            config.getProviders().forEach(p -> providers.put(p.getName(), p));
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
                            LlmConfig.Provider provider =
                                    runtime.providers.get(inv.getArgument(0, String.class));
                            return provider == null
                                    ? Optional.empty()
                                    : Optional.of(viewOf(provider));
                        });
        when(configCenter.bootLlmProviderBaseUrl(anyString()))
                .thenAnswer(
                        inv -> {
                            LlmConfig.Provider provider =
                                    runtime.providers.get(inv.getArgument(0, String.class));
                            return provider == null ? null : provider.getBaseUrl();
                        });
        return configCenter;
    }

    /** 固定单 provider stub（adapter 单测：无热改诉求）。 */
    static ConfigCenter stubOf(LlmConfig.Provider... providers) {
        LlmConfig config = LlmConfigTest.configWith(providers);
        return stub(new Runtime(config));
    }
}
