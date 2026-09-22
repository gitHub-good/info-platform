package com.info.platform.application.ai;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * LLM 成本报表应用服务（T30）：窗口聚合 llm_call_log（今日/近7天/近30天）+ 预算余量告警状态。
 *
 * <p>接口层 {@code LlmCostReportController} 经本服务读领域端口，隔离 Controller 与基础设施。 个人量级窗口行数有限（单用户日预算 20000
 * token ≈ 数次调用），取窗口全量行在应用层内存聚合（SQL GROUP BY 收益不抵复杂度）； {@code MAX_WINDOW_ROWS} 仅作护栏。
 *
 * <p>日界取 {@link Clock} 系统时区（对齐用户「今日」直觉）；留痕时间戳为 UTC ISO-8601 文本，仓储按字典序比较即时间序。 预算口径：今日各用户 SUCCESS 行
 * token 合计（缓存命中/失败/拒绝均为 0 token，天然不计）。
 */
@Service
public class LlmCostReportService {

    /** 支持的时间窗键（前端切换项）。 */
    static final Set<String> SUPPORTED_WINDOWS = Set.of("today", "7d", "30d");

    /** 窗口取数上限护栏：超出按最新 N 条聚合（个人量级不会触达）。 */
    static final int MAX_WINDOW_ROWS = 10_000;

    /** 预算列表展示的 Top 用户数。 */
    static final int TOP_USERS = 5;

    private final LlmCallLogRepository repository;
    private final LlmCostBudget budgetPolicy;
    private final Clock clock;

    /** 双构造器场景标主构造（测试构造包私有注入固定时钟）。 */
    @Autowired
    public LlmCostReportService(LlmCallLogRepository repository, LlmCostBudget budgetPolicy) {
        this(repository, budgetPolicy, Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟控制窗口边界与日界。 */
    LlmCostReportService(LlmCallLogRepository repository, LlmCostBudget budgetPolicy, Clock clock) {
        this.repository = repository;
        this.budgetPolicy = budgetPolicy;
        this.clock = clock;
    }

    /**
     * 聚合成本报表。
     *
     * @param windowKey 时间窗键 today/7d/30d；null/空默认 7d
     * @throws BusinessException {@link ErrorCode#PARAM_INVALID} 不支持的时间窗
     */
    public LlmCostReport report(String windowKey) {
        String window = (windowKey == null || windowKey.isBlank()) ? "7d" : windowKey.trim();
        if (!SUPPORTED_WINDOWS.contains(window)) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "window 仅支持 today/7d/30d，收到：" + window);
        }
        Instant windowStart = windowStart(window);
        List<LlmCallLog> rows = repository.findCreatedSince(windowStart, MAX_WINDOW_ROWS);
        List<LlmCallLog> todayRows =
                "today".equals(window)
                        ? rows
                        : repository.findCreatedSince(todayStart(), MAX_WINDOW_ROWS);
        return aggregate(window, windowStart, rows, todayRows);
    }

    private Instant windowStart(String window) {
        return switch (window) {
            case "today" -> todayStart();
            case "7d" -> clock.instant().minus(Duration.ofDays(7));
            case "30d" -> clock.instant().minus(Duration.ofDays(30));
            default -> throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "window 仅支持 today/7d/30d，收到：" + window);
        };
    }

    private Instant todayStart() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    }

    private LlmCostReport aggregate(
            String window, Instant windowStart, List<LlmCallLog> rows, List<LlmCallLog> todayRows) {
        long success = countBy(rows, LlmCallStatus.SUCCESS);
        long failed = countBy(rows, LlmCallStatus.FAILED);
        long rejected = countBy(rows, LlmCallStatus.REJECTED);
        long cacheHits = rows.stream().filter(LlmCallLog::isCacheHit).count();
        long promptTokens = rows.stream().mapToLong(LlmCallLog::getPromptTokens).sum();
        long completionTokens = rows.stream().mapToLong(LlmCallLog::getCompletionTokens).sum();
        long costMicros = rows.stream().mapToLong(LlmCallLog::getCostMicros).sum();

        return new LlmCostReport(
                window,
                windowStart.toString(),
                rows.size(),
                success,
                failed,
                rejected,
                cacheHits,
                ratio(success, success + failed),
                ratio(cacheHits, rows.size()),
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                costMicros,
                budgetPolicy.dailyBudgetTokens(),
                budgetPolicy.budgetWarnRatio(),
                byProvider(rows),
                byScene(rows),
                topUserBudgets(todayRows));
    }

    private static long countBy(List<LlmCallLog> rows, LlmCallStatus status) {
        return rows.stream().filter(r -> r.getStatus() == status).count();
    }

    /** 比率四舍五入到 4 位小数（0~1）；分母为 0（空窗口）返回 0。 */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(numerator * 10_000.0 / denominator) / 10_000.0;
    }

    private static List<LlmCostReport.ProviderCost> byProvider(List<LlmCallLog> rows) {
        record Acc(long calls, long success, long failed, long tokens, long cost) {}

        Map<String, Acc> grouped = new LinkedHashMap<>();
        for (LlmCallLog row : rows) {
            String key = row.getProviderKey() == null ? "未发起" : row.getProviderKey();
            Acc acc = grouped.getOrDefault(key, new Acc(0, 0, 0, 0, 0));
            grouped.put(
                    key,
                    new Acc(
                            acc.calls() + 1,
                            acc.success() + (row.getStatus() == LlmCallStatus.SUCCESS ? 1 : 0),
                            acc.failed() + (row.getStatus() == LlmCallStatus.FAILED ? 1 : 0),
                            acc.tokens() + row.totalTokens(),
                            acc.cost() + row.getCostMicros()));
        }
        return grouped.entrySet().stream()
                .map(
                        e ->
                                new LlmCostReport.ProviderCost(
                                        e.getKey(),
                                        e.getValue().calls(),
                                        e.getValue().success(),
                                        e.getValue().failed(),
                                        e.getValue().tokens(),
                                        e.getValue().cost()))
                .sorted(Comparator.comparingLong(LlmCostReport.ProviderCost::costMicros).reversed())
                .toList();
    }

    private static List<LlmCostReport.SceneCost> byScene(List<LlmCallLog> rows) {
        Map<String, long[]> grouped = new LinkedHashMap<>(); // [calls, tokens, cost]
        for (LlmCallLog row : rows) {
            long[] acc = grouped.computeIfAbsent(row.getSceneKey(), k -> new long[3]);
            acc[0]++;
            acc[1] += row.totalTokens();
            acc[2] += row.getCostMicros();
        }
        return grouped.entrySet().stream()
                .map(
                        e ->
                                new LlmCostReport.SceneCost(
                                        e.getKey(),
                                        e.getValue()[0],
                                        e.getValue()[1],
                                        e.getValue()[2]))
                .sorted(Comparator.comparingLong(LlmCostReport.SceneCost::costMicros).reversed())
                .toList();
    }

    private List<LlmCostReport.UserBudget> topUserBudgets(List<LlmCallLog> todayRows) {
        long budget = budgetPolicy.dailyBudgetTokens();
        long warnLine = Math.round(budget * budgetPolicy.budgetWarnRatio());
        Map<Long, Long> usedByUser = new LinkedHashMap<>();
        for (LlmCallLog row : todayRows) {
            if (row.getUserId() > 0) {
                usedByUser.merge(row.getUserId(), (long) row.totalTokens(), Long::sum);
            }
        }
        return usedByUser.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(TOP_USERS)
                .map(
                        e -> {
                            long used = e.getValue();
                            LlmBudgetStatus status =
                                    used >= budget
                                            ? LlmBudgetStatus.EXHAUSTED
                                            : used >= warnLine
                                                    ? LlmBudgetStatus.WARNING
                                                    : LlmBudgetStatus.NORMAL;
                            return new LlmCostReport.UserBudget(
                                    e.getKey(), used, budget, Math.max(0, budget - used), status);
                        })
                .toList();
    }
}
