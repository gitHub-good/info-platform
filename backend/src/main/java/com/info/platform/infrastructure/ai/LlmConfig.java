package com.info.platform.infrastructure.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 网关配置（绑定 {@code llm.*}，基础设施层，对齐 ADR-0008 + Spike-2 §9）。
 *
 * <p>配置驱动多 provider：{@code providers} 列表（name + base-url + model + api-key 走环境变量不硬编码 + enabled +
 * default + fallback）；DeepSeek 默认 + GLM fallback，预留通义/文心/Kimi（enabled=false）。 api-key 缺失默认空串（mock
 * 测试/无 key 不 fail-fast），生产经环境变量 {@code DEEPSEEK_API_KEY}/{@code GLM_API_KEY} 注入。
 *
 * <p>{@link #providerByName}/{@link #defaultProviderName}/{@link #fallbackChain} 供 {@code
 * LlmGatewayImpl} 编排 provider 选择与 fallback 切换。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private List<Provider> providers = new ArrayList<>();

    /** 单次调用超时（异步任务上限 30s，对齐技术方案 §4.3 流程 2）。 */
    private long timeoutSeconds = 30;

    /** 同 provider 重试次数（幂等只读）；M2 以 fallback 链承担重试语义，此值预留。 */
    private int retry = 1;

    /** 单用户日 token 预算（成本上限，对齐 §4.4 / Spike-2 §8.3）。 */
    private long dailyTokenBudgetPerUser = 20000;

    /**
     * 预算告警阈值比例（0~1）：用量达 {@code dailyTokenBudgetPerUser × 本值} 即告警（T30）—— 网关记 WARN 日志 + 成本报表置 WARNING
     * 状态，耗尽（100%）才拦截。默认 0.8（留 20% 余量，Spike-2 §8.3 个人量级）。
     */
    private double budgetWarnRatio = 0.8;

    private Cache cache = new Cache();

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getRetry() {
        return retry;
    }

    public void setRetry(int retry) {
        this.retry = retry;
    }

    public long getDailyTokenBudgetPerUser() {
        return dailyTokenBudgetPerUser;
    }

    public void setDailyTokenBudgetPerUser(long dailyTokenBudgetPerUser) {
        this.dailyTokenBudgetPerUser = dailyTokenBudgetPerUser;
    }

    public double getBudgetWarnRatio() {
        return budgetWarnRatio;
    }

    public void setBudgetWarnRatio(double budgetWarnRatio) {
        this.budgetWarnRatio = budgetWarnRatio;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    /** 单次调用超时。 */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /** 按配置键查 provider；不存在返回 null（adapter 据此 inert）。 */
    public Provider providerByName(String name) {
        if (name == null || providers == null) {
            return null;
        }
        return providers.stream().filter(p -> name.equals(p.getName())).findFirst().orElse(null);
    }

    /** 默认 provider 配置键（{@code default: true}）；无则 null。 */
    public String defaultProviderName() {
        return providers.stream()
                .filter(Provider::isDefault)
                .map(Provider::getName)
                .findFirst()
                .orElse(null);
    }

    /**
     * fallback 链：从 default 起按 {@code fallback} 字段串联，带环检测。
     *
     * <p>例：deepseek(default,fallback=glm)→glm(fallback=deepseek,已访问→止) = [deepseek, glm]。
     */
    public List<String> fallbackChain() {
        String start = defaultProviderName();
        if (start == null) {
            return List.of();
        }
        List<String> chain = new ArrayList<>();
        java.util.Set<String> visited = new HashSet<>();
        String current = start;
        while (current != null && visited.add(current)) {
            chain.add(current);
            Provider p = providerByName(current);
            current =
                    (p == null || p.getFallback() == null || p.getFallback().isBlank())
                            ? null
                            : p.getFallback();
        }
        return chain;
    }

    /** 单厂商配置。 */
    public static class Provider {
        private String name;
        private String baseUrl;
        private String model;

        /** 环境变量注入，永不入文件；缺失默认空串。 */
        private String apiKey;

        private boolean enabled = true;

        /** 默认 provider（yaml {@code default: true}）。字段名避开保留字，setter 为 {@code setDefault}。 */
        private boolean isDefault = false;

        private String fallback;

        /**
         * 输入单价（元/百万 token，T30 成本估算）。公开定价来自 Spike-2 §8.1（DeepSeek-flash 空闲档 1.0 / 高峰 2.0）； 免费档 GLM
         * 与未确认厂商默认 0（不估算）。厂商调价只改配置，不动代码。
         */
        private double inputPricePerMillion = 0;

        /** 输出单价（元/百万 token），口径同 {@link #inputPricePerMillion}（flash 空闲档 4.0 / 高峰 8.0）。 */
        private double outputPricePerMillion = 0;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public void setDefault(boolean isDefault) {
            this.isDefault = isDefault;
        }

        public String getFallback() {
            return fallback;
        }

        public void setFallback(String fallback) {
            this.fallback = fallback;
        }

        public double getInputPricePerMillion() {
            return inputPricePerMillion;
        }

        public void setInputPricePerMillion(double inputPricePerMillion) {
            this.inputPricePerMillion = inputPricePerMillion;
        }

        public double getOutputPricePerMillion() {
            return outputPricePerMillion;
        }

        public void setOutputPricePerMillion(double outputPricePerMillion) {
            this.outputPricePerMillion = outputPricePerMillion;
        }
    }

    /** 缓存配置（对齐 ADR-0005，TTL 按 brief_type）。 */
    public static class Cache {
        private long defaultTtlSeconds = 3600;
        private long maximumSize = 1000;
        private final Map<String, Long> ttl = new LinkedHashMap<>();

        public long getDefaultTtlSeconds() {
            return defaultTtlSeconds;
        }

        public void setDefaultTtlSeconds(long defaultTtlSeconds) {
            this.defaultTtlSeconds = defaultTtlSeconds;
        }

        public long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
        }

        public Map<String, Long> getTtl() {
            return ttl;
        }

        public void setTtl(Map<String, Long> ttl) {
            this.ttl.clear();
            if (ttl != null) {
                this.ttl.putAll(ttl);
            }
        }

        /** 简报类型键 → TTL（yaml 键 {@code brief-type-<key>}）。 */
        public Duration ttlFor(String briefTypeKey) {
            Long seconds = ttl.get("brief-type-" + briefTypeKey);
            return Duration.ofSeconds(seconds != null ? seconds : defaultTtlSeconds);
        }
    }
}
