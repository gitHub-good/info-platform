package com.info.platform.infrastructure.ai;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 域权威缺省值（ADR-0020，自 application.yml {@code llm:} 段原样迁移，页面配置即唯一真相）。
 *
 * <p>三处用途共用同一份常量，保证不漂移（对齐 T36 {@code DataSourceDefaults} 先例）： ① {@code LlmRuntimeConfigSeeder}
 * 种子值（首启导入 runtime_config，页面改过即以 DB 为权威）； ② {@code ConfigCenter} LLM 域 键缺失时的回落； ③ 纯构造单测（无 Spring
 * 上下文）。api-key 不在此列：环境变量为一等来源（ADR-0018， DB 密文 &gt; 环境变量 &gt; 空），经 {@link #envApiKey} 按需解析。
 *
 * <p>本类只承载「出厂缺省」；厂商调价、模型切换、启停等运维动作一律走页面（runtime_config DB 持久化， ADR-0017），不改代码。
 */
public final class LlmDefaults {

    /** 单次调用超时（秒；异步任务上限 30s，对齐技术方案 §4.3 流程 2）。 */
    public static final long TIMEOUT_SECONDS = 30;

    /** 同 provider 重试次数（幂等只读）；M2 以 fallback 链承担重试语义，此值预留。 */
    public static final int RETRY = 1;

    /** 单用户日 token 预算（成本上限，对齐 §4.4 / Spike-2 §8.3，~4 次/日/用户）。 */
    public static final long DAILY_TOKEN_BUDGET_PER_USER = 20000;

    /** 预算告警阈值比例（(0,1]）：用量达 80% 记 WARN + 报表置 WARNING，耗尽 100% 才拦截（T30）。 */
    public static final double BUDGET_WARN_RATIO = 0.8;

    /** 缓存默认 TTL（秒）。 */
    public static final long CACHE_DEFAULT_TTL_SECONDS = 3600;

    /** 缓存容量上限（Caffeine 红线：必设 maximumSize + 过期，防 OOM 防陈旧；建缓存时固化，RESTART 级）。 */
    public static final long CACHE_MAXIMUM_SIZE = 1000;

    /** 缓存 TTL 按 brief_type 分档（Spike-2 §9：个股/政策解读 1h、每日推荐 24h）。 */
    public static final Map<String, Long> CACHE_TTL_SECONDS;

    static {
        Map<String, Long> ttl = new LinkedHashMap<>();
        ttl.put("brief-type-1", 3600L);
        ttl.put("brief-type-3", 3600L);
        ttl.put("brief-type-4", 86400L);
        CACHE_TTL_SECONDS = Collections.unmodifiableMap(ttl);
    }

    /** 单厂商缺省配置（api-key 不在其中：走环境变量或页面录入，永不内置代码）。 */
    public record Provider(
            String name,
            String baseUrl,
            String model,
            boolean enabled,
            boolean isDefault,
            String fallback,
            double inputPricePerMillion,
            double outputPricePerMillion) {

        /**
         * 输入/输出单价（元/百万 token，T30 成本估算）。公开定价来自 Spike-2 §8.1（DeepSeek-flash 空闲档 1.0/4.0；高峰 2.0/8.0
         * 按需在页面调）；免费档 GLM 与未确认厂商为 0（不估算）。
         */
    }

    /**
     * 全部 provider 缺省（ADR-0008：DeepSeek default + GLM fallback，通义/Kimi 预留 {@code enabled=false}，
     * 按需启用各加 provider adapter）。
     */
    private static final List<Provider> PROVIDERS =
            List.of(
                    new Provider(
                            "deepseek",
                            "https://api.deepseek.com",
                            "deepseek-flash",
                            true,
                            true,
                            "glm",
                            1.0,
                            4.0),
                    new Provider(
                            "glm",
                            "https://open.bigmodel.cn/api/paas/v4",
                            "glm-4-flash-250414",
                            true,
                            false,
                            "deepseek",
                            0,
                            0),
                    new Provider(
                            "qwen",
                            "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "qwen-plus",
                            false,
                            false,
                            "deepseek",
                            0,
                            0),
                    new Provider(
                            "kimi",
                            "https://api.moonshot.cn/v1",
                            "moonshot-v1-8k",
                            false,
                            false,
                            "deepseek",
                            0,
                            0));

    private LlmDefaults() {}

    /** 全部 provider 缺省（只读，顺序即展示顺序）。 */
    public static List<Provider> providers() {
        return PROVIDERS;
    }

    /** 按名称查 provider 缺省；不存在返回空。 */
    public static Optional<Provider> providerByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return PROVIDERS.stream().filter(p -> name.equals(p.name())).findFirst();
    }

    /** 单次调用超时（Duration 形态，网关直接可用）。 */
    public static Duration timeout() {
        return Duration.ofSeconds(TIMEOUT_SECONDS);
    }

    /** 简报类型键 → 缓存 TTL；未分档回落默认 TTL。 */
    public static Duration ttlFor(String briefTypeKey) {
        Long seconds = CACHE_TTL_SECONDS.get("brief-type-" + briefTypeKey);
        return Duration.ofSeconds(seconds != null ? seconds : CACHE_DEFAULT_TTL_SECONDS);
    }

    /** provider 的环境变量名（纯映射，便于单测）：provider 名大写 + {@code _API_KEY}，如 {@code DEEPSEEK_API_KEY}。 */
    public static String apiKeyEnvVar(String providerName) {
        return providerName.toUpperCase(Locale.ROOT) + "_API_KEY";
    }

    /**
     * provider 环境变量 key 解析（一等来源，ADR-0018）。缺失或空白返回 null（无 key 不 fail-fast，mock 测试/未配置场景照常运行）。 全部 ENV
     * key 读取收敛经本方法——消费点单测以 mockStatic 本类为注入点（System 本身不可 mock）。
     */
    public static String envApiKey(String providerName) {
        String raw = providerName == null ? null : System.getenv(apiKeyEnvVar(providerName));
        return raw == null || raw.isBlank() ? null : raw;
    }
}
