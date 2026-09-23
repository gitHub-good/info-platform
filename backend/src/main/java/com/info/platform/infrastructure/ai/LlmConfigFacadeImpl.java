package com.info.platform.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.ai.LlmConfigFacade;
import com.info.platform.application.ai.LlmConfigView;
import com.info.platform.application.ai.LlmConfigView.ApiKeyView;
import com.info.platform.application.ai.LlmConfigView.GlobalConfigView;
import com.info.platform.application.ai.LlmConfigView.ProviderConfigView;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.ConfigSecretCipher;
import com.info.platform.infrastructure.common.RuntimeLlmProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * LLM 配置管理实现（T35，方案 §4.4.1 + ADR-0017/0018）。落在基础设施层的原因与端口说明见 {@link LlmConfigFacade}。
 *
 * <p>读：runtime_config 快照经 {@link ConfigCenter}/{@link RuntimeConfigService}，key 解析顺序 DB 密文 &gt;
 * 环境变量 &gt; 空（回显仅脱敏态）。写：当前文档合并请求字段（PATCH 语义）→ {@link RuntimeConfigService#write}（校验 + 乐观防呆 + 换快照 +
 * 发事件）；{@code isDefault=true} 互斥置反其他 provider（全局恰一）。API key 写入 AES-256-GCM
 * 加密落库，明文不落日志（日志只记键级「已更新」）。
 *
 * <p>连通性测试：当前运行时配置直调 adapter（绕缓存、不占用户预算），scene_key={@code test} 落 {@code
 * llm_call_log}（成本按调用时点单价落死值，供报表可见）。
 */
@Component
public class LlmConfigFacadeImpl implements LlmConfigFacade {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigFacadeImpl.class);

    /** 连通性测试留痕场景键（方案 §4.4.1）。 */
    static final String CONNECTIVITY_SCENE_KEY = "test";

    /** 连通性测试 max_tokens（最小成本探活）。 */
    private static final int CONNECTIVITY_MAX_TOKENS = 16;

    private static final Map<String, String> GLOBAL_EFFECTIVE_MODES =
            Map.of(
                    "timeoutSeconds", LlmConfigView.EFFECTIVE_LIVE,
                    "retry", LlmConfigView.EFFECTIVE_LIVE,
                    "dailyTokenBudgetPerUser", LlmConfigView.EFFECTIVE_LIVE,
                    "budgetWarnRatio", LlmConfigView.EFFECTIVE_LIVE,
                    "cacheDefaultTtlSeconds", LlmConfigView.EFFECTIVE_LIVE,
                    "cacheTtlSeconds", LlmConfigView.EFFECTIVE_LIVE,
                    "cacheMaximumSize", LlmConfigView.EFFECTIVE_RESTART);

    private static final Map<String, String> PROVIDER_EFFECTIVE_MODES =
            Map.of(
                    "model", LlmConfigView.EFFECTIVE_LIVE,
                    "enabled", LlmConfigView.EFFECTIVE_LIVE,
                    "isDefault", LlmConfigView.EFFECTIVE_LIVE,
                    "fallback", LlmConfigView.EFFECTIVE_LIVE,
                    "inputPricePerMillion", LlmConfigView.EFFECTIVE_LIVE,
                    "outputPricePerMillion", LlmConfigView.EFFECTIVE_LIVE,
                    "baseUrl", LlmConfigView.EFFECTIVE_RESTART,
                    "apiKey", LlmConfigView.EFFECTIVE_LIVE);

    private final RuntimeConfigService configService;
    private final ConfigCenter configCenter;
    private final ConfigSecretCipher cipher;
    private final List<LlmProviderAdapter> adapters;
    private final LlmCallLogger callLog;
    private final LlmCostGuard costGuard;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;

    public LlmConfigFacadeImpl(
            RuntimeConfigService configService,
            ConfigCenter configCenter,
            ConfigSecretCipher cipher,
            List<LlmProviderAdapter> adapters,
            LlmCallLogger callLog,
            LlmCostGuard costGuard,
            @Qualifier("llmExecutor") ExecutorService executor,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.configCenter = configCenter;
        this.cipher = cipher;
        this.adapters = List.copyOf(adapters);
        this.callLog = callLog;
        this.costGuard = costGuard;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmConfigView view() {
        return new LlmConfigView(globalView(), providerViews(), cipher.enabled());
    }

    @Override
    public GlobalConfigView updateGlobal(LlmGlobalUpdate update) {
        ObjectNode merged = mutableDoc(ConfigCenter.KEY_LLM_GLOBAL, defaultsGlobalDoc());
        if (update.timeoutSeconds() != null) {
            merged.put("timeoutSeconds", update.timeoutSeconds());
        }
        if (update.retry() != null) {
            merged.put("retry", update.retry());
        }
        if (update.dailyTokenBudgetPerUser() != null) {
            merged.put("dailyTokenBudgetPerUser", update.dailyTokenBudgetPerUser());
        }
        if (update.budgetWarnRatio() != null) {
            merged.put("budgetWarnRatio", update.budgetWarnRatio());
        }
        if (update.cacheDefaultTtlSeconds() != null) {
            merged.put("cacheDefaultTtlSeconds", update.cacheDefaultTtlSeconds());
        }
        if (update.cacheTtlSeconds() != null) {
            merged.set("cacheTtlSeconds", toJson(update.cacheTtlSeconds()));
        }
        RuntimeConfigEntry saved =
                write(ConfigCenter.KEY_LLM_GLOBAL, merged, update.expectedUpdatedAt());
        return globalView(saved);
    }

    @Override
    public ProviderConfigView updateProvider(String name, LlmProviderUpdate update) {
        requireKnownProvider(name);
        String key = ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name;
        ObjectNode merged = mutableDoc(key, defaultsProviderDoc(name));
        if (update.model() != null) {
            merged.put("model", update.model());
        }
        if (update.enabled() != null) {
            merged.put("enabled", update.enabled());
        }
        if (update.isDefault() != null) {
            merged.put("isDefault", update.isDefault());
        }
        if (update.fallback() != null) {
            merged.put("fallback", update.fallback());
        }
        if (update.inputPricePerMillion() != null) {
            merged.put("inputPricePerMillion", update.inputPricePerMillion());
        }
        if (update.outputPricePerMillion() != null) {
            merged.put("outputPricePerMillion", update.outputPricePerMillion());
        }
        if (update.baseUrl() != null) {
            merged.put("baseUrl", update.baseUrl());
        }
        write(key, merged, update.expectedUpdatedAt());
        if (Boolean.TRUE.equals(update.isDefault())) {
            demoteOtherDefaults(name);
        }
        return providerView(name);
    }

    @Override
    public ProviderConfigView writeApiKey(String name, LlmApiKeyWrite update) {
        requireKnownProvider(name);
        String plaintext = update.apiKey();
        if (plaintext == null || plaintext.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "apiKey: 不能为空");
        }
        // 未配置 CONFIG_SECRET 时 encrypt 抛 30064（接口层映射 503，页面只读降级，ADR-0018）
        String encrypted = cipher.encrypt(plaintext);
        String key = ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name;
        ObjectNode merged = mutableDoc(key, defaultsProviderDoc(name));
        merged.put("apiKeyCipher", encrypted);
        merged.put("apiKeyLast4", ConfigSecretCipher.last4(plaintext));
        write(key, merged, update.expectedUpdatedAt());
        // 明文不落日志：只记键级审计痕迹（方案 §5 可观测）
        log.info("LLM provider {} api-key 已更新（来源=DB 密文）", name);
        return providerView(name);
    }

    @Override
    public LlmConnectivityResult connectivityTest(String name) {
        RuntimeLlmProvider provider =
                configCenter
                        .provider(name)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.LLM_PROVIDER_NOT_FOUND, name));
        LlmProviderAdapter adapter =
                adapters.stream().filter(a -> name.equals(a.name())).findFirst().orElse(null);
        if (adapter == null) {
            return new LlmConnectivityResult(false, null, null, "该 provider 暂无接入 adapter");
        }
        long startNanos = System.nanoTime();
        try {
            LlmResponse resp = callAdapterWithTimeout(adapter, provider);
            long latency = elapsedMillis(startNanos);
            recordConnectivity(provider, resp, latency);
            return new LlmConnectivityResult(true, latency, resp.model(), null);
        } catch (Exception e) {
            long latency = elapsedMillis(startNanos);
            recordConnectivityFailure(provider, e, latency);
            return new LlmConnectivityResult(false, latency, null, summarize(e));
        }
    }

    // —— 读视图 ——

    private GlobalConfigView globalView() {
        return configService
                .read(ConfigCenter.KEY_LLM_GLOBAL)
                .map(this::globalView)
                .orElseGet(() -> globalView(null));
    }

    private GlobalConfigView globalView(RuntimeConfigEntry entry) {
        JsonNode doc = entry == null ? defaultsGlobalDoc() : entry.document();
        Map<String, Long> ttl = new LinkedHashMap<>();
        doc.path("cacheTtlSeconds")
                .fields()
                .forEachRemaining(f -> ttl.put(f.getKey(), f.getValue().asLong()));
        return new GlobalConfigView(
                doc.path("timeoutSeconds").asLong(),
                doc.path("retry").asInt(),
                doc.path("dailyTokenBudgetPerUser").asLong(),
                doc.path("budgetWarnRatio").asDouble(),
                doc.path("cacheDefaultTtlSeconds").asLong(),
                ttl,
                LlmDefaults.CACHE_MAXIMUM_SIZE,
                costGuard.currentUsage(currentUserId()),
                entry == null ? null : entry.updatedAt().toString(),
                GLOBAL_EFFECTIVE_MODES);
    }

    private List<ProviderConfigView> providerViews() {
        return LlmDefaults.providers().stream().map(p -> providerView(p.name())).toList();
    }

    private ProviderConfigView providerView(String name) {
        RuntimeConfigEntry entry =
                configService.read(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name).orElse(null);
        JsonNode doc = entry == null ? defaultsProviderDoc(name) : entry.document();
        String cipherText = doc.path("apiKeyCipher").asText("");
        boolean fromDb = !cipherText.isBlank();
        String envKey = LlmDefaults.envApiKey(name);
        boolean fromEnv = !fromDb && envKey != null;
        ApiKeyView apiKey =
                fromDb
                        ? new ApiKeyView(
                                ApiKeyView.STATUS_CONFIGURED,
                                RuntimeLlmProvider.ApiKeySource.DB.name(),
                                emptyToNull(doc.path("apiKeyLast4").asText(null)))
                        : fromEnv
                                ? new ApiKeyView(
                                        ApiKeyView.STATUS_CONFIGURED,
                                        RuntimeLlmProvider.ApiKeySource.ENV.name(),
                                        ConfigSecretCipher.last4(envKey))
                                : new ApiKeyView(ApiKeyView.STATUS_NOT_SET, null, null);
        return new ProviderConfigView(
                name,
                doc.path("model").asText(null),
                doc.path("enabled").asBoolean(),
                doc.path("isDefault").asBoolean(),
                emptyToNull(doc.path("fallback").asText(null)),
                doc.path("baseUrl").asText(null),
                LlmConfigView.EFFECTIVE_RESTART,
                doc.path("inputPricePerMillion").asDouble(),
                doc.path("outputPricePerMillion").asDouble(),
                apiKey,
                entry == null ? null : entry.updatedAt().toString(),
                PROVIDER_EFFECTIVE_MODES);
    }

    // —— 写路径（合并 → 校验落库 → 互斥默认） ——

    /** 当前文档为基（无键时以内置缺省文档为基），返回可变副本供字段合并。 */
    private ObjectNode mutableDoc(String configKey, ObjectNode defaultsBase) {
        JsonNode current =
                configService
                        .read(configKey)
                        .map(RuntimeConfigEntry::document)
                        .orElse(defaultsBase);
        // 文档均为 JSON 对象（种子/校验器保证），deepCopy 后可安全改写
        return (ObjectNode) current.deepCopy();
    }

    private RuntimeConfigEntry write(
            String configKey, ObjectNode merged, String expectedUpdatedAt) {
        return configService.write(configKey, merged.toString(), parseExpected(expectedUpdatedAt));
    }

    private void demoteOtherDefaults(String promotedName) {
        for (LlmDefaults.Provider provider : LlmDefaults.providers()) {
            String name = provider.name();
            if (name.equals(promotedName)) {
                continue;
            }
            String key = ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name;
            configService
                    .read(key)
                    .ifPresent(
                            entry -> {
                                if (entry.document().path("isDefault").asBoolean(false)) {
                                    ObjectNode merged = entry.document().deepCopy();
                                    merged.put("isDefault", false);
                                    configService.write(key, merged.toString(), null);
                                    log.info("LLM provider {} 因 {} 设为默认，互斥置反", name, promotedName);
                                }
                            });
        }
    }

    private void requireKnownProvider(String name) {
        if (LlmDefaults.providerByName(name).isEmpty()) {
            throw new BusinessException(
                    ErrorCode.LLM_PROVIDER_NOT_FOUND, "provider " + name + " 不存在");
        }
    }

    private Instant parseExpected(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "expectedUpdatedAt: 须为 ISO-8601 时刻（如 2026-09-22T01:00:00Z）");
        }
    }

    // —— 连通性测试 ——

    private LlmResponse callAdapterWithTimeout(
            LlmProviderAdapter adapter, RuntimeLlmProvider provider) throws Exception {
        LlmRequest probe =
                new LlmRequest(
                        List.of(new ChatMessage("user", "ping")),
                        provider.model(),
                        0.1,
                        CONNECTIVITY_MAX_TOKENS,
                        null,
                        CONNECTIVITY_SCENE_KEY);
        Duration timeout =
                configCenter.llmGlobal().map(g -> g.timeout()).orElseGet(LlmDefaults::timeout);
        Future<LlmResponse> future = executor.submit(() -> adapter.chat(probe));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw new IllegalStateException("连通性测试执行异常", cause);
        }
    }

    private void recordConnectivity(
            RuntimeLlmProvider provider, LlmResponse resp, long latencyMillis) {
        LlmUsage usage = resp.usage() == null ? new LlmUsage(0, 0) : resp.usage();
        // 成本按调用时点单价落死值（调价不回溯，ADR-0015）
        long costMicros =
                LlmCallLog.estimateCostMicros(
                        usage.promptTokens(),
                        usage.completionTokens(),
                        provider.inputPricePerMillion(),
                        provider.outputPricePerMillion());
        LlmCallLog entry = LlmCallLog.begin(currentUserId(), CONNECTIVITY_SCENE_KEY);
        entry.markSuccess(provider.name(), resp.model(), usage, costMicros, latencyMillis);
        callLog.record(entry);
    }

    private void recordConnectivityFailure(
            RuntimeLlmProvider provider, Exception error, long latencyMillis) {
        LlmCallLog entry = LlmCallLog.begin(currentUserId(), CONNECTIVITY_SCENE_KEY);
        entry.markFailed(
                "连通性测试失败 provider=" + provider.name() + ": " + summarize(error), latencyMillis);
        callLog.record(entry);
    }

    private static String summarize(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static long currentUserId() {
        UserContext.Principal p = UserContext.get();
        return p == null ? 0L : p.userId();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ObjectNode toJson(Map<String, Long> values) {
        ObjectNode node = objectMapper.createObjectNode();
        values.forEach(node::put);
        return node;
    }

    /** 内置缺省的全局文档（DB 无键时的合并基与展示兜底，值同种子）。 */
    private ObjectNode defaultsGlobalDoc() {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("timeoutSeconds", LlmDefaults.TIMEOUT_SECONDS);
        doc.put("retry", LlmDefaults.RETRY);
        doc.put("dailyTokenBudgetPerUser", LlmDefaults.DAILY_TOKEN_BUDGET_PER_USER);
        doc.put("budgetWarnRatio", LlmDefaults.BUDGET_WARN_RATIO);
        doc.put("cacheDefaultTtlSeconds", LlmDefaults.CACHE_DEFAULT_TTL_SECONDS);
        doc.set("cacheTtlSeconds", toJson(LlmDefaults.CACHE_TTL_SECONDS));
        return doc;
    }

    /** 内置缺省的 provider 文档（DB 无键时的合并基与展示兜底，值同种子）。 */
    private ObjectNode defaultsProviderDoc(String name) {
        LlmDefaults.Provider defaults = LlmDefaults.providerByName(name).orElse(null);
        ObjectNode doc = objectMapper.createObjectNode();
        if (defaults == null) {
            return doc;
        }
        doc.put("model", defaults.model());
        doc.put("enabled", defaults.enabled());
        doc.put("isDefault", defaults.isDefault());
        if (defaults.fallback() != null) {
            doc.put("fallback", defaults.fallback());
        }
        doc.put("inputPricePerMillion", defaults.inputPricePerMillion());
        doc.put("outputPricePerMillion", defaults.outputPricePerMillion());
        if (defaults.baseUrl() != null) {
            doc.put("baseUrl", defaults.baseUrl());
        }
        return doc;
    }
}
