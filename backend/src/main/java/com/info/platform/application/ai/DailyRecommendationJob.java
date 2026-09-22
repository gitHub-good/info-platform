package com.info.platform.application.ai;

import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.JobScheduleSettings;
import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每日推荐盘前预热 Job（应用层，T23，对齐技术方案 §4.1.6「盘前生成」+ 方案 08 定时任务 + ADR-0006；T37 收编 {@link ManagedJob}）。
 *
 * <p>对配置的用户列表预热生成当日每日推荐，使 GET /recommendations/daily 首次请求命中幂等缓存直返（避免 on-demand 首请求阻塞等待 LLM 3~8s）。
 *
 * <p>调度（T37 集中化，ADR-0017）：去 @Scheduled/条件注解后无条件装配，由 JobScheduler 按 {@code job.DAILY_RECOMMEND}
 * 运行时配置注册（CRON，种子默认 {@code 0 0 9 * * ?} 且 {@code enabled=false}——生产按需 页面开启）；测试 profile 种子 {@code
 * enabled=false} → 零注册，隔离语义等价平移。
 *
 * <p>用户列表（T37 热改）：每轮执行时从 {@code job.DAILY_RECOMMEND.userIds}（逗号分隔）现读——页面改列表自下次
 * 执行生效，无需重启。个人/小团队量级单用户场景配一个 user-id 即可；多用户盘前预热待 WatchlistRepository 增加 {@code
 * findActiveOwnerUserIds()} 端口后演进（见自测报告遗留）。空列表时空跑记 DEBUG（不报错）。
 */
@Component
public class DailyRecommendationJob implements ManagedJob {

    private static final Logger log = LoggerFactory.getLogger(DailyRecommendationJob.class);

    private final DailyRecommendationService service;
    private final RuntimeConfigService runtimeConfig;

    public DailyRecommendationJob(
            DailyRecommendationService service, RuntimeConfigService runtimeConfig) {
        this.service = service;
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public String jobKey() {
        return "DAILY_RECOMMEND";
    }

    @Override
    public String displayName() {
        return "每日推荐";
    }

    @Override
    public String description() {
        return "盘前对配置用户预热生成当日每日推荐（cron 调度）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口（委托 {@link #prefetchDaily}）。 */
    @Override
    public void run() {
        prefetchDaily();
    }

    /** 盘前预热（CRON 默认每日 09:00，可配）。 */
    public void prefetchDaily() {
        List<Long> userIds = currentUserIds();
        if (userIds.isEmpty()) {
            log.debug("每日推荐盘前预热：未配置 user-ids，跳过");
            return;
        }
        for (Long userId : userIds) {
            try {
                DailyRecommendationResult result = service.generateDaily(userId);
                log.info(
                        "每日推荐盘前预热完成 userId={} status={} top={}",
                        userId,
                        result.status(),
                        result.topRecommend().size());
            } catch (Exception e) {
                // 单用户预热异常不阻断其余用户（同 AnomalyDetectionJob 单 item 容错模式）
                log.warn("每日推荐盘前预热异常 userId={}: {}", userId, e.toString());
            }
        }
    }

    /** 当前生效的预热用户列表（每轮现读 runtime_config，改列表自下次执行生效）。 */
    private List<Long> currentUserIds() {
        return runtimeConfig
                .read(JobScheduleSettings.KEY_PREFIX + jobKey())
                .map(RuntimeConfigEntry::document)
                .map(JobScheduleSettings::from)
                .map(JobScheduleSettings::userIds)
                .map(DailyRecommendationJob::parseUserIds)
                .orElse(List.of());
    }

    /** 解析逗号分隔 user-id 列表（空/非数字跳过）。 */
    private static List<Long> parseUserIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(
                        s -> {
                            try {
                                return Long.valueOf(s);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }
}
