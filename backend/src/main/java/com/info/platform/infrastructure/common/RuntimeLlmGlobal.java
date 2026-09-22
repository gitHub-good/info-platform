package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Map;

/**
 * {@code llm.global} 键的类型化只读视图（T34，供 T35 消费点用时读取）。
 *
 * <p>字段对齐方案 §4.1 键空间表；由 {@code ConfigCenter#llmGlobal()} 从内存快照解析。 消费点（网关超时/守卫预算/缓存
 * TTL）每次使用时经视图取当前值——保存即生效（LIVE 级，ADR-0017）。
 *
 * @param timeoutSeconds 单次调用超时（秒）
 * @param retry 同 provider 重试次数
 * @param dailyTokenBudgetPerUser 单用户日 token 预算
 * @param budgetWarnRatio 预算告警阈值（(0,1]）
 * @param cacheDefaultTtlSeconds 缓存默认 TTL（秒）
 * @param cacheTtlSeconds 简报类型分档 TTL（键形如 "brief-type-1"，秒）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeLlmGlobal(
        long timeoutSeconds,
        int retry,
        long dailyTokenBudgetPerUser,
        double budgetWarnRatio,
        long cacheDefaultTtlSeconds,
        Map<String, Long> cacheTtlSeconds) {

    /** 单次调用超时（Duration 形态，网关直接可用）。 */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /** 简报类型键 → TTL；未分档回落默认 TTL（对齐 LlmConfig.Cache#ttlFor 语义）。 */
    public Duration ttlFor(String briefTypeKey) {
        Long seconds =
                cacheTtlSeconds == null ? null : cacheTtlSeconds.get("brief-type-" + briefTypeKey);
        return Duration.ofSeconds(seconds != null ? seconds : cacheDefaultTtlSeconds);
    }
}
