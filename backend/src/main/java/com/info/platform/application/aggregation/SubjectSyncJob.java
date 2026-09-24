package com.info.platform.application.aggregation;

import com.info.platform.application.jobrun.ManagedJob;
import com.info.platform.application.jobrun.ScheduleType;
import java.util.List;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 标的池同步 Job（T53，技术方案增补 §4.6）：收编 {@link ManagedJob}——注册即权威，任务中心自动可见、手动可触发、CRON 可热切换（ADR-0017
 * 既有体系零改动）。
 *
 * <p>调度（对照 DAILY_RECOMMEND 先例）：CRON 型，种子 {@code job.SUBJECT_SYNC} 由 {@code JobRuntimeConfigSeeder}
 * 播种（yml {@code subject.sync.enabled/cron}，生产默认启用、每日 06:00 开市前裕量）；测试 profile 种子 {@code
 * enabled=false} → 调度零注册，隔离语义等价平移。
 *
 * <p>留痕双通道（§3.3 方案 A）：成功轮计数走应用 INFO 日志（{@link #run()} 末尾单行摘要，processed = 新增 + 更新）； 部分失败轮由 {@link
 * SubjectSyncService#syncAll} 抛 {@link SubjectSyncException}（携带各桶计数与失败摘要）→ 本类<b>不吞异常</b>，
 * JobExecutor 落 job_execution_log.error_message（Job 日志页可见，§4.5 格式）。 防重入（手动 + 定时并发）由 JobExecutor CAS
 * 守卫拦截，本类无需自防。
 */
@Component
public class SubjectSyncJob implements ManagedJob {

    private static final Logger log = LoggerFactory.getLogger(SubjectSyncJob.class);

    private final SubjectSyncService subjectSyncService;

    public SubjectSyncJob(SubjectSyncService subjectSyncService) {
        this.subjectSyncService = subjectSyncService;
    }

    @Override
    public String jobKey() {
        return "SUBJECT_SYNC";
    }

    @Override
    public String displayName() {
        return "标的池同步";
    }

    @Override
    public String description() {
        return "每日全量拉取 A 股/港股上市股票与沪深指数，自动新增/更新标的池（退市连续 3 轮确认停用，永不删除）";
    }

    @Override
    public ScheduleType scheduleType() {
        return ScheduleType.CRON;
    }

    /** 定时与手动触发共用入口：委托同步引擎；任一股票桶失败时异常直抛（errorMessage 留痕通道）。 */
    @Override
    public void run() {
        List<MarketSyncResult> results = subjectSyncService.syncAll();
        log.info(
                "标的池同步完成（Job 留痕摘要）: {} | processed={}（新增 {} + 更新 {}），停用 {}，源总行数 {}",
                joinSummaries(results),
                totalOf(results, MarketSyncResult::inserted)
                        + totalOf(results, MarketSyncResult::updated),
                totalOf(results, MarketSyncResult::inserted),
                totalOf(results, MarketSyncResult::updated),
                totalOf(results, MarketSyncResult::deactivated),
                totalOf(results, MarketSyncResult::total));
    }

    private static String joinSummaries(List<MarketSyncResult> results) {
        StringBuilder joined = new StringBuilder();
        for (MarketSyncResult result : results) {
            if (!joined.isEmpty()) {
                joined.append("; ");
            }
            joined.append(result.summary());
        }
        return joined.toString();
    }

    /** 各桶计数求和（INFO 摘要行的 processed/停用/总行数口径）。 */
    private static int totalOf(
            List<MarketSyncResult> results, ToIntFunction<MarketSyncResult> getter) {
        int total = 0;
        for (MarketSyncResult result : results) {
            total += getter.applyAsInt(result);
        }
        return total;
    }
}
