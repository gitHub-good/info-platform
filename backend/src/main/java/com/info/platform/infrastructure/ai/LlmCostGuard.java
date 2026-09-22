package com.info.platform.infrastructure.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 成本上限守卫（ADR-0004 / ADR-0008 + 技术方案 §4.4 成本上限 + Spike-2 §8.3；T30 调优）。
 *
 * <p>单用户日 token 预算：Caffeine 按 <b>日界键</b> {@code userId:yyyy-MM-dd} 计数—— T19 的 {@code
 * expireAfterWrite(24h)} 只是从首次写入起算的近似日重置（T19 注释明示「严格跨日重置留 T30」）， 换日后取新键计数自然归零，实现严格跨日重置； 旧键条目由
 * {@code expireAfterWrite(48h)} 兜底逐出（覆盖单日最长写入跨度，只作清理不影响计数）。 日界取 {@link Clock}
 * 系统时区（对齐用户「当日」直觉），测试经构造注入 {@code Clock.fixed} 模拟换日。
 *
 * <p>调用前 {@link #checkBudget} 拦截（已用尽抛 {@link BusinessException}({@link
 * ErrorCode#AI_QUOTA_EXHAUSTED}，429 语义，对齐技术方案 §4.1.4 {@code 30030})）；成功后 {@link #recordUsage}
 * 计入实际用量，并在<b>首次越过告警线</b>（{@code 预算 × budgetWarnRatio}，默认 0.8）时记一次 WARN（跨线一次为限，不逐次刷屏）， 留痕表另落
 * REJECTED 行（T30 报表告警状态同源本阈值）。
 *
 * <p>实现领域端口 {@link LlmCostBudget}（预检 + 预算策略查询），供应用层 {@code AIBriefService} POST 预检注入端口
 * 而非本实现类（守护分层：避 application↔infrastructure 循环依赖）；计费入账由 {@code LlmGatewayImpl} 调本类 {@link
 * #recordUsage} 完成。
 *
 * <p>check/record 间存在 TOCTOU 窗口（个人量级可接受）：单次简报 ~4900 token / 预算 20000，
 * 单次不会大幅越界，越界由下次调用拦截。计数为内存态，重启清零（严格跨日的权威口径以 llm_call_log 报表为准，观测与执行分离）。
 *
 * <p>{@code userId <= 0}（无认证上下文，如系统定时任务）跳过限流——异步 Worker 须显式设置 {@code UserContext} 才能计入配额。
 */
public class LlmCostGuard implements LlmCostBudget {

    private static final Logger log = LoggerFactory.getLogger(LlmCostGuard.class);

    /** 预算告警阈值默认值（0~1，正式值经 {@code llm.budget-warn-ratio} 配置注入）。 */
    static final double DEFAULT_WARN_RATIO = 0.8;

    /** 日界键条目保留时长：覆盖单日最长写入跨度即可，仅作过期清理（换日即取新键，不影响计数）。 */
    private static final Duration ENTRY_RETENTION = Duration.ofHours(48);

    private final long dailyBudget;
    private final double warnRatio;
    private final Clock clock;
    private final Cache<String, Long> used;

    /** 便捷构造：默认告警阈值 0.8 + 系统时钟（单测/默认场景）。 */
    public LlmCostGuard(long dailyBudget) {
        this(dailyBudget, DEFAULT_WARN_RATIO, Clock.systemDefaultZone());
    }

    public LlmCostGuard(long dailyBudget, double warnRatio, Clock clock) {
        this.dailyBudget = dailyBudget;
        this.warnRatio = warnRatio;
        this.clock = clock;
        this.used =
                Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ENTRY_RETENTION).build();
    }

    /**
     * 检查当日预算：已用 {@code >= dailyBudget} 抛成本上限异常。
     *
     * @param userId 用户 ID（{@code <=0} 跳过）
     */
    @Override
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

    /**
     * 计入实际用量（按 {@code usage.totalTokens}）；{@code userId <=0} 或 usage null 跳过。 首次越过告警线（{@code 预算 ×
     * budgetWarnRatio}）记一次 WARN。
     */
    public void recordUsage(long userId, LlmUsage usage) {
        if (userId <= 0 || usage == null) {
            return;
        }
        String key = dayKey(userId);
        long before = rawUsage(key);
        long after = used.asMap().merge(key, (long) usage.totalTokens(), Long::sum);
        if (before < warnLine() && after >= warnLine()) {
            log.warn(
                    "用户 {} 当日 LLM token 用量越过告警线（{}/{}，阈值 {}），余量 {}",
                    userId,
                    after,
                    dailyBudget,
                    warnRatio,
                    Math.max(0, dailyBudget - after));
        }
    }

    /** 当前已用量（取当日日界键；测试可见）。 */
    public long currentUsage(long userId) {
        if (userId <= 0) {
            return 0L;
        }
        return rawUsage(dayKey(userId));
    }

    /** 预算上限（端口暴露，报表同源）。 */
    @Override
    public long dailyBudgetTokens() {
        return dailyBudget;
    }

    /** 预算告警阈值比例（端口暴露，报表同源）。 */
    @Override
    public double budgetWarnRatio() {
        return warnRatio;
    }

    private long rawUsage(String key) {
        Long v = used.getIfPresent(key);
        return v == null ? 0L : v;
    }

    private long warnLine() {
        return Math.round(dailyBudget * warnRatio);
    }

    /** 日界键：{@code userId:yyyy-MM-dd}（系统时区），换日取新键即严格跨日重置。 */
    private String dayKey(long userId) {
        return userId + ":" + java.time.LocalDate.now(clock);
    }
}
