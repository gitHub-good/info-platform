package com.info.platform.infrastructure.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Duration;

/**
 * LLM 成本上限守卫（ADR-0004 / ADR-0008 + 技术方案 §4.4 成本上限 + Spike-2 §8.3）。
 *
 * <p>单用户日 token 预算：Caffeine 按 {@code userId} 计数，{@code expireAfterWrite(24h)} 近似日重置（个人量级足够； 严格跨日重置留
 * T30）。调用前 {@link #checkBudget} 拦截（已用尽抛 {@link BusinessException}({@link
 * ErrorCode#AI_QUOTA_EXHAUSTED}，429 语义，对齐技术方案 §4.1.4 {@code 30030}））；成功后 {@link #recordUsage}
 * 计入实际用量。
 *
 * <p>check/record 间存在 TOCTOU 窗口（个人量级可接受）：单次简报 ~4900 token / 预算 20000， 单次不会大幅越界，越界由下次调用拦截。
 *
 * <p>{@code userId <= 0}（无认证上下文，如系统定时任务）跳过限流——T21 异步 Worker 须显式设置 {@code UserContext} 才计入配额（M2
 * 同步调用由请求入口已写入）。
 */
public class LlmCostGuard {

    private final long dailyBudget;
    private final Cache<Long, Long> used;

    public LlmCostGuard(long dailyBudget) {
        this.dailyBudget = dailyBudget;
        this.used =
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofDays(1))
                        .build();
    }

    /**
     * 检查当日预算：已用 {@code >= dailyBudget} 抛成本上限异常。
     *
     * @param userId 用户 ID（{@code <=0} 跳过）
     */
    public void checkBudget(long userId) {
        if (userId <= 0) {
            return;
        }
        long current = currentUsage(userId);
        if (current >= dailyBudget) {
            throw new BusinessException(
                    ErrorCode.AI_QUOTA_EXHAUSTED,
                    "用户 " + userId + " 当日 LLM token 配额已用尽（" + current + "/" + dailyBudget + "）");
        }
    }

    /** 计入实际用量（按 {@code usage.totalTokens}）；{@code userId <=0} 或 usage null 跳过。 */
    public void recordUsage(long userId, LlmUsage usage) {
        if (userId <= 0 || usage == null) {
            return;
        }
        used.asMap().merge(userId, (long) usage.totalTokens(), Long::sum);
    }

    /** 当前已用量（测试可见）。 */
    public long currentUsage(long userId) {
        Long v = used.getIfPresent(userId);
        return v == null ? 0L : v;
    }

    /** 预算上限（测试可见）。 */
    public long dailyBudget() {
        return dailyBudget;
    }
}
