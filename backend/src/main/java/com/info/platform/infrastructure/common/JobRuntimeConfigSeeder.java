package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 任务域种子（{@code job.*} 5 键，T34）。
 *
 * <p>键值对照方案 §4.1「任务键与既有 Job 对照表」：jobKey/jobName 对齐既有 yml 开关与 {@code job_execution_log.job_name}。
 * 种子值取当前 yml（测试 profile 各开关为 false → 种子 enabled=false → T37 调度中心零注册，隔离语义等价平移）。消费与 校验器随 T37 落地。
 */
@Component
public class JobRuntimeConfigSeeder implements RuntimeConfigSeeder {

    /** scheduleType 取值（对齐方案 §4.1 键空间表）。 */
    private static final String FIXED_DELAY = "FIXED_DELAY";

    private static final String CRON = "CRON";

    private final ObjectMapper objectMapper;

    @Value("${policy.fetch.enabled:true}")
    private boolean policyFetchEnabled;

    @Value("${policy.fetch.interval-millis:3600000}")
    private long policyFetchIntervalMillis;

    @Value("${policy.tendency.enabled:true}")
    private boolean policyTendencyEnabled;

    @Value("${policy.tendency.interval-millis:1800000}")
    private long policyTendencyIntervalMillis;

    @Value("${anomaly.detect.enabled:true}")
    private boolean anomalyDetectEnabled;

    @Value("${anomaly.detect-interval-millis:10000}")
    private long anomalyDetectIntervalMillis;

    @Value("${push.retry.enabled:true}")
    private boolean pushRetryEnabled;

    @Value("${push.retry.interval-millis:30000}")
    private long pushRetryIntervalMillis;

    @Value("${recommendation.schedule.enabled:false}")
    private boolean dailyRecommendEnabled;

    @Value("${recommendation.schedule.cron:0 0 9 * * ?}")
    private String dailyRecommendCron;

    @Value("${recommendation.schedule.user-ids:}")
    private String dailyRecommendUserIds;

    public JobRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        seeds.add(
                fixedDelay(
                        "POLICY_FETCH",
                        "政策抓取任务调度（PolicyFetchJob，gov.cn/zhengce 抓取去重入库）",
                        policyFetchEnabled,
                        policyFetchIntervalMillis));
        seeds.add(
                fixedDelay(
                        "POLICY_TENDENCY",
                        "政策倾向判断任务调度（PolicyTendencyJob，LLM 批量判利好/利空/中性）",
                        policyTendencyEnabled,
                        policyTendencyIntervalMillis));
        seeds.add(
                fixedDelay(
                        "ANOMALY_DETECT",
                        "异动检测任务调度（AnomalyDetectionJob，活跃标的行情阈值轮询）",
                        anomalyDetectEnabled,
                        anomalyDetectIntervalMillis));
        seeds.add(
                fixedDelay(
                        "PUSH_RETRY",
                        "推送补推任务调度（PushRetryJob，未消费异动与失败推送补拉）",
                        pushRetryEnabled,
                        pushRetryIntervalMillis));
        Map<String, Object> daily = new LinkedHashMap<>();
        daily.put("enabled", dailyRecommendEnabled);
        daily.put("scheduleType", CRON);
        daily.put("cron", dailyRecommendCron);
        daily.put("userIds", dailyRecommendUserIds == null ? "" : dailyRecommendUserIds);
        seeds.add(
                new RuntimeConfigSeed(
                        "job.DAILY_RECOMMEND", write(daily), "每日推荐盘前预热调度（DailyRecommendationJob）"));
        return seeds;
    }

    private RuntimeConfigSeed fixedDelay(
            String jobKey, String description, boolean enabled, long intervalMillis) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("enabled", enabled);
        doc.put("scheduleType", FIXED_DELAY);
        doc.put("intervalMillis", intervalMillis);
        return new RuntimeConfigSeed("job." + jobKey, write(doc), description);
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
