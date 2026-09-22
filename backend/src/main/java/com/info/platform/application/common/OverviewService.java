package com.info.platform.application.common;

import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.aggregation.DataSourceConfigFacade.SourceCardView;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import com.info.platform.domain.common.JobExecutionStatus;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import com.info.platform.domain.push.AnomalyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 概览仪表盘聚合应用服务（T42，方案 §4.4.4 / §4.6，REQ 故事 5）：一次返回五卡片数据。
 *
 * <p>取数口径（方案 §4.6 裁定，全部复用既有仓储/端口，不另起炉灶）：
 *
 * <ul>
 *   <li>成本水位：今日日界（系统时区 00:00，对齐 {@code LlmCostReportService}）；预算取 {@link LlmCostBudget} 运行时当前值（T34
 *       配置中心热值，执行与观测同源）；状态三态阈值同成本报表（warnLine = 预算 × 告警比例）。
 *   <li>今日异动：{@code anomaly_event.trigger_time} 落今日行数（今日日界，非滚动窗口——有意裁定，对齐用户当日直觉）。
 *   <li>政策动态：{@code policy_item.created_at} 滚动 24h 行数 + 最新 5 条（裁定用入库时间而非 published_at，防历史回填计数失真）。
 *   <li>任务健康：{@code job_execution_log.created_at} 滚动 24h 执行总数/FAILED 数（索引列；与 start_time 同刻写入语义等价） +
 *       unhealthyJobs（窗口内失败&gt;0 或最近一次执行 FAILED 的 jobKey）。
 *   <li>数据源健康：复用 {@link DataSourceConfigFacade}（最近一条事件含 OK 心跳 + 24h 异常计数 + 运行时 mode）。
 * </ul>
 *
 * <p><b>探活剔除裁定（T35 遗留，本任务落地）</b>：连通性测试留痕（scene_key={@code test}，见 {@code
 * LlmConfigFacadeImpl.CONNECTIVITY_SCENE_KEY}）为运维探活非业务消耗，<b>剔除</b>出概览今日
 * token/成本（成本报表页仍全量可见，两处口径各自独立）。
 *
 * <p><b>卡片级容错</b>：各卡片独立取数独立 try/catch——单卡数据源失败仅该卡置 error 态（数值 0/空列表），不拖垮整页（UI 方案 §3.1 error
 * 态「单卡失败」分支）。 失败记 WARN（可自动恢复、重试即愈，不到 ERROR）。单人本地量级，内部串行取数即可（方案未要求并行）。
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    /** 连通性测试留痕场景键（对齐 {@code LlmConfigFacadeImpl.CONNECTIVITY_SCENE_KEY}，探活剔除口径）。 */
    static final String CONNECTIVITY_SCENE_KEY = "test";

    /** llm_call_log 今日取数护栏（沿用 T30 LlmCostReportService 的 1 万行上限，个人量级不会触达）。 */
    static final int MAX_WINDOW_ROWS = 10_000;

    /** 政策卡最新条数（方案 §4.4.4 契约 latest 5 条）。 */
    static final int POLICY_LATEST_LIMIT = 5;

    /** 窗口内失败行扫描护栏（仅 FAILED 行，个人量级失败为少数）。 */
    static final int FAILED_SCAN_LIMIT = 500;

    private final LlmCallLogRepository llmCallLogRepository;
    private final LlmCostBudget budgetPolicy;
    private final AnomalyRepository anomalyRepository;
    private final PolicyRepository policyRepository;
    private final JobExecutionLogRepository jobLogRepository;
    private final DataSourceConfigFacade dataSourceConfigFacade;
    private final JobRegistry jobRegistry;
    private final Clock clock;

    /** 双构造器场景标主构造（测试构造包私有注入固定时钟）。 */
    @Autowired
    public OverviewService(
            LlmCallLogRepository llmCallLogRepository,
            LlmCostBudget budgetPolicy,
            AnomalyRepository anomalyRepository,
            PolicyRepository policyRepository,
            JobExecutionLogRepository jobLogRepository,
            DataSourceConfigFacade dataSourceConfigFacade,
            JobRegistry jobRegistry) {
        this(
                llmCallLogRepository,
                budgetPolicy,
                anomalyRepository,
                policyRepository,
                jobLogRepository,
                dataSourceConfigFacade,
                jobRegistry,
                Clock.systemDefaultZone());
    }

    /** 测试构造：注入固定时钟控制今日日界与滚动 24h 边界。 */
    OverviewService(
            LlmCallLogRepository llmCallLogRepository,
            LlmCostBudget budgetPolicy,
            AnomalyRepository anomalyRepository,
            PolicyRepository policyRepository,
            JobExecutionLogRepository jobLogRepository,
            DataSourceConfigFacade dataSourceConfigFacade,
            JobRegistry jobRegistry,
            Clock clock) {
        this.llmCallLogRepository = llmCallLogRepository;
        this.budgetPolicy = budgetPolicy;
        this.anomalyRepository = anomalyRepository;
        this.policyRepository = policyRepository;
        this.jobLogRepository = jobLogRepository;
        this.dataSourceConfigFacade = dataSourceConfigFacade;
        this.jobRegistry = jobRegistry;
        this.clock = clock;
    }

    /** 五卡片聚合视图（GET /api/v1/overview）。 */
    public OverviewView view() {
        SourceHealthResult sourceHealth =
                guard(
                        "sourceHealth",
                        this::sourceHealth,
                        error -> new SourceHealthResult(List.of(), error));
        return new OverviewView(
                guard("llmToday", this::llmToday, error -> new LlmTodayCard(0, 0, 0, "OK", error)),
                guard("anomalyToday", this::anomalyToday, error -> new AnomalyTodayCard(0, error)),
                guard(
                        "policy24h",
                        this::policy24h,
                        error -> new Policy24hCard(0, List.of(), error)),
                guard(
                        "jobHealth",
                        this::jobHealth,
                        error -> new JobHealthCard(0, 0, List.of(), error)),
                sourceHealth.sources(),
                sourceHealth.error());
    }

    // —— 各卡片取数（口径见类注释；异常经 view() 的 guard 统一收敛为单卡 error 态） ——

    private LlmTodayCard llmToday() {
        List<LlmCallLog> rows =
                llmCallLogRepository.findCreatedSince(todayStart(), MAX_WINDOW_ROWS);
        // 探活剔除：scene_key=test 的留痕是配置页连通性测试产生的探活消耗，不计入业务水位
        List<LlmCallLog> businessRows =
                rows.stream().filter(r -> !CONNECTIVITY_SCENE_KEY.equals(r.getSceneKey())).toList();
        long tokenUsed =
                businessRows.stream()
                        .filter(r -> r.getStatus() == LlmCallStatus.SUCCESS)
                        .mapToLong(LlmCallLog::totalTokens)
                        .sum();
        // cost_micros 仅 SUCCESS 行非 0（失败/拒绝/缓存命中均 0），全行求和口径与报表一致且天然不含探活成本
        long costMicros = businessRows.stream().mapToLong(LlmCallLog::getCostMicros).sum();
        long budget = budgetPolicy.dailyBudgetTokens();
        long warnLine = Math.round(budget * budgetPolicy.budgetWarnRatio());
        String status =
                tokenUsed >= budget ? "EXHAUSTED" : tokenUsed >= warnLine ? "WARNING" : "OK";
        return new LlmTodayCard(tokenUsed, costMicros, budget, status, null);
    }

    private AnomalyTodayCard anomalyToday() {
        return new AnomalyTodayCard(anomalyRepository.countTriggeredSince(todayStart()), null);
    }

    private Policy24hCard policy24h() {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        long count = policyRepository.countCreatedSince(since);
        List<PolicyLatestItem> latest =
                policyRepository.findLatestCreatedSince(since, POLICY_LATEST_LIMIT).stream()
                        .map(OverviewService::toLatestItem)
                        .toList();
        return new Policy24hCard(count, latest, null);
    }

    private JobHealthCard jobHealth() {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        long runs = jobLogRepository.countSince(since);
        long failed = jobLogRepository.countFailedSince(since);
        Set<String> unhealthy = new LinkedHashSet<>();
        Map<String, String> keyByJobName = keyByJobName();
        for (JobExecutionLog row : jobLogRepository.findFailedSince(since, FAILED_SCAN_LIMIT)) {
            unhealthy.add(keyByJobName.getOrDefault(row.getJobName(), row.getJobName()));
        }
        for (ManagedJob job : jobRegistry.jobs()) {
            List<JobExecutionLog> latest = jobLogRepository.byJobNameCursor(job.jobName(), null, 1);
            if (!latest.isEmpty() && latest.get(0).getStatus() == JobExecutionStatus.FAILED) {
                unhealthy.add(job.jobKey());
            }
        }
        return new JobHealthCard(runs, failed, List.copyOf(unhealthy), null);
    }

    private SourceHealthResult sourceHealth() {
        List<SourceHealthItem> sources =
                dataSourceConfigFacade.view().sources().stream()
                        .map(OverviewService::toSourceItem)
                        .toList();
        return new SourceHealthResult(sources, null);
    }

    // —— 私有辅助 ——

    /** 今日 00:00（系统时区），对齐 {@code LlmCostReportService} 日界口径。 */
    private Instant todayStart() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    }

    /** jobName → jobKey 映射（unhealthyJobs 输出 jobKey；未知 jobName 原样回退，防脏数据丢信息）。 */
    private Map<String, String> keyByJobName() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (ManagedJob job : jobRegistry.jobs()) {
            mapping.put(job.jobName(), job.jobKey());
        }
        return mapping;
    }

    private static PolicyLatestItem toLatestItem(PolicyItem item) {
        return new PolicyLatestItem(
                item.getId(),
                item.getTitle(),
                item.getPublishedAt() == null ? null : item.getPublishedAt().toString());
    }

    private static SourceHealthItem toSourceItem(SourceCardView card) {
        return new SourceHealthItem(
                card.sourceCode(),
                card.mode(),
                card.health().lastEventType(),
                card.health().lastEventAt(),
                card.health().errors24h());
    }

    /** 单卡取数守卫：失败记 WARN（带卡片名与异常摘要）并返回该卡 error 态（errorView 构造零值卡 + 错误文案）。 */
    private <T> T guard(String card, Supplier<T> loader, Function<String, T> errorView) {
        try {
            return loader.get();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("概览卡片取数失败 card={}: {}", card, message);
            return errorView.apply("取数失败：" + message);
        }
    }

    /** GET /api/v1/overview 响应（五卡聚合；sourceHealth 为契约裸数组，其卡级错误置兄弟字段 sourceHealthError）。 */
    public record OverviewView(
            LlmTodayCard llmToday,
            AnomalyTodayCard anomalyToday,
            Policy24hCard policy24h,
            JobHealthCard jobHealth,
            List<SourceHealthItem> sourceHealth,
            String sourceHealthError) {}

    /** 成本水位卡：status 三态 OK/WARNING/EXHAUSTED（契约 §4.4.4，阈值同成本报表）。 */
    public record LlmTodayCard(
            long tokenUsed, long costMicros, long budgetTokens, String status, String error) {}

    /** 今日异动卡（今日日界计数）。 */
    public record AnomalyTodayCard(long count, String error) {}

    /** 政策动态卡（滚动 24h 计数 + 最新 5 条）。 */
    public record Policy24hCard(long count, List<PolicyLatestItem> latest, String error) {}

    /** 政策最新条目（契约 §4.4.4：id/title/publishedAt）。 */
    public record PolicyLatestItem(long id, String title, String publishedAt) {}

    /** 任务健康卡（滚动 24h 执行数/失败数 + 不健康任务 jobKey 列表）。 */
    public record JobHealthCard(
            long windowRuns, long windowFailed, List<String> unhealthyJobs, String error) {}

    /** 数据源健康项（复用 DataSourceConfigFacade 健康口径：最近一条事件 + 24h 异常计数 + 运行时 mode）。 */
    public record SourceHealthItem(
            String sourceCode,
            String mode,
            String lastEventType,
            String lastEventAt,
            long errors24h) {}

    /** sourceHealth 卡内部聚合结果（列表 + 卡级错误，view() 拆为契约字段）。 */
    private record SourceHealthResult(List<SourceHealthItem> sources, String error) {}
}
