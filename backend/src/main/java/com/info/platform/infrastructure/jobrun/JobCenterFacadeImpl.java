package com.info.platform.infrastructure.jobrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.info.platform.application.common.JobLogView;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobRegistry;
import com.info.platform.application.jobrun.JobScheduleSettings;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.JobExecutionLog;
import com.info.platform.domain.common.JobExecutionLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link JobCenterFacade} 实现（T37，方案 §4.4.3）。落在基础设施层的原因见端口 Javadoc。
 *
 * <p>读：JobRegistry 元数据 + runtime_config 调度设置 + {@code job_execution_log} 最近执行 + JobExecutor 运行态 +
 * JobScheduler 下次执行推算。写：当前文档合并请求字段（PATCH 语义）→ {@link RuntimeConfigService#write}（校验 + 乐观防呆 + 换快照 +
 * 发事件）——调度中心订阅事件热重注册，启停立即、 间隔/cron 下一周期生效（ADR-0017）。手动触发：{@link JobExecutor#triggerNow} 异步受理。
 */
@Component
public class JobCenterFacadeImpl implements JobCenterFacade {

    private static final Logger log = LoggerFactory.getLogger(JobCenterFacadeImpl.class);

    /** 生效级别（T35/T36 effectiveModes 先例 + ADR-0017 任务分级：启停立即、间隔/cron 下一周期）。 */
    private static final String EFFECTIVE_LIVE = "LIVE";

    private static final String EFFECTIVE_LIVE_NEXT_CYCLE = "LIVE_NEXT_CYCLE";

    private static final Map<String, String> EFFECTIVE_MODES =
            Map.of(
                    "enabled", EFFECTIVE_LIVE,
                    "intervalMillis", EFFECTIVE_LIVE_NEXT_CYCLE,
                    "cron", EFFECTIVE_LIVE_NEXT_CYCLE,
                    "userIds", EFFECTIVE_LIVE_NEXT_CYCLE);

    /** 详情页最近执行条数（方案 §4.4.3「含最近 5 次执行」）。 */
    private static final int RECENT_EXECUTIONS = 5;

    private final JobRegistry registry;
    private final RuntimeConfigService configService;
    private final JobExecutionLogRepository logRepository;
    private final JobExecutor jobExecutor;
    private final JobScheduler jobScheduler;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public JobCenterFacadeImpl(
            JobRegistry registry,
            RuntimeConfigService configService,
            JobExecutionLogRepository logRepository,
            JobExecutor jobExecutor,
            JobScheduler jobScheduler,
            Clock clock,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.configService = configService;
        this.logRepository = logRepository;
        this.jobExecutor = jobExecutor;
        this.jobScheduler = jobScheduler;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public JobsView view() {
        return new JobsView(registry.jobs().stream().map(this::jobView).toList());
    }

    @Override
    public JobDetailView detail(String jobKey) {
        ManagedJob job = requireJob(jobKey);
        List<JobLogView> recent =
                logRepository.byJobNameCursor(job.jobName(), null, RECENT_EXECUTIONS).stream()
                        .map(JobLogView::from)
                        .toList();
        return new JobDetailView(jobView(job), recent);
    }

    @Override
    public JobView update(String jobKey, JobConfigUpdate update) {
        ManagedJob job = requireJob(jobKey);
        String key = JobScheduleSettings.KEY_PREFIX + job.jobKey();
        ObjectNode merged = mutableDoc(job);
        if (update.enabled() != null) {
            merged.put("enabled", update.enabled());
        }
        if (update.intervalMillis() != null) {
            merged.put("intervalMillis", update.intervalMillis());
        }
        if (update.cron() != null) {
            merged.put("cron", update.cron());
        }
        if (update.userIds() != null) {
            merged.put("userIds", update.userIds());
        }
        RuntimeConfigEntry saved =
                configService.write(
                        key, merged.toString(), parseExpected(update.expectedUpdatedAt()));
        log.info("任务调度已更新 jobKey={}（键级审计，值不入日志）", job.jobKey());
        return jobView(job, saved);
    }

    @Override
    public TriggerResult trigger(String jobKey) {
        ManagedJob job = requireJob(jobKey);
        JobExecutor.TriggerOutcome outcome = jobExecutor.triggerNow(job);
        return new TriggerResult(outcome.executionId(), "STARTED");
    }

    // —— 读视图 ——

    private JobView jobView(ManagedJob job) {
        return jobView(
                job,
                configService.read(JobScheduleSettings.KEY_PREFIX + job.jobKey()).orElse(null));
    }

    private JobView jobView(ManagedJob job, RuntimeConfigEntry entry) {
        JobScheduleSettings settings =
                entry == null
                        ? JobScheduleSettings.DISABLED
                        : JobScheduleSettings.from(entry.document());
        return new JobView(
                job.jobKey(),
                job.jobName(),
                job.displayName(),
                job.description(),
                job.scheduleType().name(),
                boxedInterval(job, settings),
                settings.cron(),
                settings.userIds(),
                settings.enabled(),
                jobExecutor.isRunning(job.jobKey()),
                lastExecutionOf(job),
                nextExecutionTimeOf(job, settings),
                entry == null ? null : entry.updatedAt().toString(),
                EFFECTIVE_MODES);
    }

    /** 间隔仅间隔型任务有值（cron 型为 null，页面按 scheduleType 分支渲染）。 */
    private static Long boxedInterval(ManagedJob job, JobScheduleSettings settings) {
        return job.scheduleType() == ScheduleType.FIXED_DELAY ? settings.intervalMillis() : null;
    }

    /** 上次执行（job_execution_log 最新一条，STARTED 视为运行中返回 STARTED；从未执行返回 null）。 */
    private LastExecutionView lastExecutionOf(ManagedJob job) {
        List<JobExecutionLog> latest = logRepository.byJobNameCursor(job.jobName(), null, 1);
        if (latest.isEmpty()) {
            return null;
        }
        JobExecutionLog logEntry = latest.get(0);
        return new LastExecutionView(
                logEntry.getStatus().name(),
                logEntry.getStartTime().toString(),
                logEntry.getEndTime().map(Object::toString).orElse(null),
                logEntry.getDurationMillis().orElse(null));
    }

    private String nextExecutionTimeOf(ManagedJob job, JobScheduleSettings settings) {
        if (!settings.enabled()) {
            return null;
        }
        return jobScheduler
                .nextExecutionTime(job.jobKey())
                .map(time -> time.truncatedTo(ChronoUnit.SECONDS).toString())
                .orElse(null);
    }

    // —— 写路径（合并 → 校验落库） ——

    /** 当前文档为基（无键时以任务元数据生成基线：scheduleType 固定、enabled=false——宁缺勿乱），返回可变副本。 */
    private ObjectNode mutableDoc(ManagedJob job) {
        JsonNode current =
                configService
                        .read(JobScheduleSettings.KEY_PREFIX + job.jobKey())
                        .map(RuntimeConfigEntry::document)
                        .orElseGet(() -> baselineDoc(job));
        return (ObjectNode) current.deepCopy();
    }

    private ObjectNode baselineDoc(ManagedJob job) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("enabled", false);
        doc.put("scheduleType", job.scheduleType().name());
        return doc;
    }

    private ManagedJob requireJob(String jobKey) {
        return registry.byJobKey(jobKey).orElseThrow(() -> unknown(jobKey));
    }

    private static BusinessException unknown(String jobKey) {
        return new BusinessException(ErrorCode.JOB_NOT_FOUND, "任务不存在: " + jobKey);
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
}
