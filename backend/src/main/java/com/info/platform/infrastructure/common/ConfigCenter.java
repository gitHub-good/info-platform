package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.common.RuntimeConfigSnapshot;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.infrastructure.ai.LlmConfig;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 配置中心（基础设施层，T34 / ADR-0017）——承担方案 §4.2 的 {@code RuntimeConfigView} 角色。
 *
 * <p>职责：① 启动就绪（{@link ApplicationReadyEvent}）汇总各域 {@link RuntimeConfigSeeder} 执行 seed-if-absent 导入
 * （DB 已有键不动，DB 为权威），并冻结启动期快照（{@link #bootDocument} 供 RESTART 级参数如 provider baseUrl）； ② 消费点用时读取
 * API：当前快照的 JSON 文档 / 类型化视图（{@link #llmGlobal()} / {@link #provider(String)}）。 快照的加载 与写时替换由 {@link
 * RuntimeConfigService} 承载，本类只读不写。
 *
 * <p>热路径零 DB 读：所有读方法走内存快照（volatile 整体替换，用时读取即热生效）。
 */
@Component
public class ConfigCenter {

    private static final Logger log = LoggerFactory.getLogger(ConfigCenter.class);

    /** 键名常量（T35~T37 消费点共用）。 */
    public static final String KEY_LLM_GLOBAL = "llm.global";

    public static final String KEY_LLM_PROVIDER_PREFIX = "llm.provider.";

    private final RuntimeConfigService configService;
    private final List<RuntimeConfigSeeder> seeders;
    private final LlmConfig llmConfig;
    private final ConfigSecretCipher cipher;
    private final ObjectMapper objectMapper;

    /** 启动期冻结快照（RESTART 级参数读取口径）；null = 尚未冻结。 */
    private volatile RuntimeConfigSnapshot bootSnapshot;

    public ConfigCenter(
            RuntimeConfigService configService,
            List<RuntimeConfigSeeder> seeders,
            LlmConfig llmConfig,
            ConfigSecretCipher cipher,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.seeders = List.copyOf(seeders);
        this.llmConfig = llmConfig;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /** 启动就绪：种子导入 + 冻结启动快照（在端口服务后执行；此前到达的读取走惰性加载兜底）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<RuntimeConfigSeed> seeds =
                seeders.stream().flatMap(seeder -> seeder.seeds().stream()).toList();
        configService.seedIfAbsent(seeds);
        bootSnapshot = configService.current();
        log.info("配置中心就绪：快照 {} 键（种子源 {} 个）", bootSnapshot.size(), seeders.size());
    }

    /** 消费点用时读取（LIVE 级）：当前快照中该键的 JSON 文档。 */
    public Optional<JsonNode> document(String configKey) {
        return configService.current().find(configKey).map(RuntimeConfigEntry::document);
    }

    /** RESTART 级参数读取（如 provider baseUrl）：启动期冻结快照，页面保存不热生效、重启后生效。 */
    public Optional<JsonNode> bootDocument(String configKey) {
        return boot().find(configKey).map(RuntimeConfigEntry::document);
    }

    /** {@code llm.global} 类型化视图；键不存在（未种子/未写入）返回空。 */
    public Optional<RuntimeLlmGlobal> llmGlobal() {
        return configService
                .current()
                .find(KEY_LLM_GLOBAL)
                .map(entry -> parse(entry, RuntimeLlmGlobal.class));
    }

    /**
     * {@code llm.provider.{name}} 类型化视图，含 API key 解析（DB 密文 &gt; 环境变量 &gt; 空串）。
     *
     * <p>密文解密失败（CONFIG_SECRET 轮换/密文损坏）不阻断读：记 WARN 后回落环境变量（ADR-0018「不禁读」），重新录入 key 后恢复 DB 优先。
     *
     * @return provider 不存在（无该键且无 yml 条目）返回空
     */
    public Optional<RuntimeLlmProvider> provider(String name) {
        Optional<RuntimeConfigEntry> entry =
                configService.current().find(KEY_LLM_PROVIDER_PREFIX + name);
        if (entry.isPresent()) {
            return Optional.of(resolveProvider(name, entry.orElseThrow()));
        }
        // 无运行时键：yml 条目兜底（种子前/种子失败的降级读取，保持 providerByName 语义）
        LlmConfig.Provider yml = llmConfig.providerByName(name);
        return yml == null ? Optional.empty() : Optional.of(fromYmlProvider(yml));
    }

    /**
     * RESTART 级 provider baseUrl（T35）：启动期冻结快照读取，页面保存不热生效、重启后生效（ADR-0017 §4.2）。
     *
     * <p>启动快照无该键（种子前/未写入）回落 yml 绑定值；均无返回 null（消费方 inert）。
     */
    public String bootLlmProviderBaseUrl(String name) {
        String fromBoot =
                boot().find(KEY_LLM_PROVIDER_PREFIX + name)
                        .map(entry -> entry.document().path("baseUrl").asText(null))
                        .filter(value -> value != null && !value.isBlank())
                        .orElse(null);
        if (fromBoot != null) {
            return fromBoot;
        }
        LlmConfig.Provider yml = llmConfig.providerByName(name);
        return yml == null ? null : yml.getBaseUrl();
    }

    private RuntimeLlmProvider resolveProvider(String name, RuntimeConfigEntry entry) {
        ProviderDoc doc = parse(entry, ProviderDoc.class);
        String apiKey = "";
        RuntimeLlmProvider.ApiKeySource source = RuntimeLlmProvider.ApiKeySource.NONE;
        String last4 = null;
        if (doc.apiKeyCipher != null && !doc.apiKeyCipher.isBlank()) {
            try {
                apiKey = cipher.decrypt(doc.apiKeyCipher);
                source = RuntimeLlmProvider.ApiKeySource.DB;
                last4 = doc.apiKeyLast4;
            } catch (BusinessException e) {
                log.warn("provider {} 密文解密失败，回落环境变量 key: {}", name, e.getMessage());
            }
        }
        if (source == RuntimeLlmProvider.ApiKeySource.NONE) {
            String envKey = envApiKey(name);
            if (envKey != null && !envKey.isBlank()) {
                apiKey = envKey;
                source = RuntimeLlmProvider.ApiKeySource.ENV;
                last4 = ConfigSecretCipher.last4(envKey);
            }
        }
        return new RuntimeLlmProvider(
                name,
                doc.model,
                doc.enabled,
                doc.isDefault,
                doc.fallback,
                doc.inputPricePerMillion,
                doc.outputPricePerMillion,
                doc.baseUrl,
                apiKey,
                source,
                last4);
    }

    private RuntimeLlmProvider fromYmlProvider(LlmConfig.Provider yml) {
        String envKey = yml.getApiKey();
        boolean envPresent = envKey != null && !envKey.isBlank();
        return new RuntimeLlmProvider(
                yml.getName(),
                yml.getModel(),
                yml.isEnabled(),
                yml.isDefault(),
                yml.getFallback(),
                yml.getInputPricePerMillion(),
                yml.getOutputPricePerMillion(),
                yml.getBaseUrl(),
                envPresent ? envKey : "",
                envPresent
                        ? RuntimeLlmProvider.ApiKeySource.ENV
                        : RuntimeLlmProvider.ApiKeySource.NONE,
                envPresent ? ConfigSecretCipher.last4(envKey) : null);
    }

    private String envApiKey(String name) {
        LlmConfig.Provider yml = llmConfig.providerByName(name);
        return yml == null ? null : yml.getApiKey();
    }

    private RuntimeConfigSnapshot boot() {
        RuntimeConfigSnapshot current = bootSnapshot;
        if (current == null) {
            synchronized (this) {
                if (bootSnapshot == null) {
                    bootSnapshot = configService.current();
                }
                current = bootSnapshot;
            }
        }
        return current;
    }

    private <T> T parse(RuntimeConfigEntry entry, Class<T> type) {
        try {
            return objectMapper.treeToValue(entry.document(), type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "runtime_config 键 "
                            + entry.configKey()
                            + " 解析为 "
                            + type.getSimpleName()
                            + " 失败: "
                            + e,
                    e);
        }
    }

    /** provider 文档的原始形状（密文字段仅在此内部结构与密文工具间流转）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderDoc(
            String model,
            boolean enabled,
            boolean isDefault,
            String fallback,
            double inputPricePerMillion,
            double outputPricePerMillion,
            String baseUrl,
            String apiKeyCipher,
            String apiKeyLast4) {}
}
