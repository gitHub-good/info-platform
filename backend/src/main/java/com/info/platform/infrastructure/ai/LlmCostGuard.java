package com.info.platform.infrastructure.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.RuntimeLlmGlobal;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 成本上限守卫（ADR-0004 / ADR-0008 + 技术方案 §4.4 成本上限 + Spike-2 §8.3；T30 调优；T35 热改）。
 *
 * <p>单用户日 token 预算：Caffeine 按 <b>日界键</b> {@code userId:yyyy-MM-dd} 计数—— 换日后取新键计数自然归零，实现严格跨日重置；
 * 旧键条目由 {@code expireAfterWrite(48h)} 兜底逐出（覆盖单日最长写入跨度，只作清理不影响计数）。 日界取 {@link Clock}
 * 系统时区（对齐用户「当日」直觉），测试经构造注入 {@code Clock.fixed} 模拟换日。
 *
 * <p><b>预算/告警阈值运行时读取</b>（T35 / ADR-0017 / 方案 §4.3）：构造注入 {@link Supplier}， {@link
 * #checkBudget}/{@link #dailyBudgetTokens}/{@link #budgetWarnRatio} 用时取当前值——页面改预算保存即对下一次调用生效
 * （报表预算上限随改随新）；<b>已用计数为内存态保留不清零</b>（改预算不动当日用量）。
 *
 * <p>调用前 {@link #checkBudget} 拦截（已用尽抛 {@link BusinessException}({@link
 * ErrorCode#AI_QUOTA_EXHAUSTED}，429 语义，对齐技术方案 §4.1.4 {@code 30030})）；成功后 {@link #recordUsage}
 * 计入实际用量，并在<b>首次越过告警线</b>（{@code 预算 × budgetWarnRatio}）时记一次 WARN（跨线一次为限，不逐次刷屏）， 留痕表另落 REJECTED
 * 行（T30 报表告警状态同源本阈值）。
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

    /** 日界键条目保留时长：覆盖单日最长写入跨度即可，仅作过期清理（换日即取新键，不影响计数）。 */
    private static final Duration ENTRY_RETENTION = Duration.ofHours(48);

    private final Supplier<RuntimeLlmGlobal> globalView;
    private final Clock clock;
    private final Cache<String, Long> used;

    /** 便捷构造：系统时钟（生产装配与多数单测）。 */
    public LlmCostGuard(Supplier<RuntimeLlmGlobal> globalView) {
        this(globalView, Clock.systemDefaultZone());
    }

    public LlmCostGuard(Supplier<RuntimeLlmGlobal> globalView, Clock clock) {
        this.globalView = globalView;
        this.clock = clock;
        this.used =
                Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ENTRY_RETENTION).build();
    }

    /**
     * 检查当日预算：已用 {@code >= 当前预算} 抛成本上限异常（预算运行时读取，改小后当次调用即拦截）。
     *
     * @param userId 用户 ID（{@code <=0} 跳过）
     */
    @Override
    public void checkBudget(long userId) {
        if (userId <= 0) {
            return;
        }
        long current = currentUsage(userId);
        long dailyBudget = dailyBudgetTokens();
        if (current >= dailyBudget) {
            throw new BusinessException(
                    ErrorCode.AI_QUOTA_EXHAUSTED,
                    "用户 " + userId + " 当日 LLM token 配额已用尽（" + current + "/" + dailyBudget + "）");
        }
    }

    /**
     * 计入实际用量（按 {@code usage.totalTokens}）；{@code userId <=0} 或 usage null 跳过。 首次越过告警线（{@code 预算 ×
     * budgetWarnRatio}，阈值运行时读取）记一次 WARN。
     */
    public void recordUsage(long userId, LlmUsage usage) {
        if (userId <= 0 || usage == null) {
            return;
        }
        String key = dayKey(userId);
        long before = rawUsage(key);
        long after = used.asMap().merge(key, (long) usage.totalTokens(), Long::sum);
        long warnLine = warnLine();
        if (before < warnLine && after >= warnLine) {
            log.warn(
                    "用户 {} 当日 LLM token 用量越过告警线（{}/{}，阈值 {}），余量 {}",
                    userId,
                    after,
                    dailyBudgetTokens(),
                    budgetWarnRatio(),
                    Math.max(0, dailyBudgetTokens() - after));
        }
    }

    /** 当前已用量（取当日日界键；测试可见）。 */
    public long currentUsage(long userId) {
        if (userId <= 0) {
            return 0L;
        }
        return rawUsage(dayKey(userId));
    }

    /** 预算上限（端口暴露，报表同源；运行时读取，随改随新）。 */
    @Override
    public long dailyBudgetTokens() {
        return globalView.get().dailyTokenBudgetPerUser();
    }

    /** 预算告警阈值比例（端口暴露，报表同源；运行时读取）。 */
    @Override
    public double budgetWarnRatio() {
        return globalView.get().budgetWarnRatio();
    }

    private long rawUsage(String key) {
        Long v = used.getIfPresent(key);
        return v == null ? 0L : v;
    }

    private long warnLine() {
        return Math.round(dailyBudgetTokens() * budgetWarnRatio());
    }

    /** 日界键：{@code userId:yyyy-MM-dd}（系统时区），换日取新键即严格跨日重置。 */
    private String dayKey(long userId) {
        return userId + ":" + java.time.LocalDate.now(clock);
    }
}
