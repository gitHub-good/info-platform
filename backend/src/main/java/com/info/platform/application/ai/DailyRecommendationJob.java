package com.info.platform.application.ai;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日推荐盘前预热 Job（应用层，T23，对齐技术方案 §4.1.6「盘前生成」+ 方案 08 定时任务 + ADR-0006）。
 *
 * <p>{@link Scheduled}（cron 可配 {@code recommendation.schedule.cron}，默认每日 09:00）对配置的用户列表预热生成当日每日推荐，
 * 使 GET /recommendations/daily 首次请求命中幂等缓存直返（避免 on-demand 首请求阻塞等待 LLM 3~8s）。
 *
 * <p>受开关控制（测试关）： {@code recommendation.schedule.enabled=false}（默认）时本 Bean 与 {@link
 * RecommendationSchedulingConfig}（{@code @EnableScheduling}）均不装配——@Scheduled 不在
 * {@code @SpringBootTest} 触发，逻辑改由单测直调 {@link DailyRecommendationService#generateDaily} 验证（对齐 04
 * 测试规范 + AnomalyDetectionJob 模式）。
 *
 * <p>用户列表： {@code recommendation.schedule.user-ids}（逗号分隔，默认空）。个人/小团队量级单用户场景配一个 user-id 即可； 多用户盘前预热待
 * WatchlistRepository 增加 {@code findActiveOwnerUserIds()} 端口后演进（见自测报告遗留）。空列表时 Job 空跑记 DEBUG（不报错）。
 */
@Component
@ConditionalOnProperty(name = "recommendation.schedule.enabled", havingValue = "true")
public class DailyRecommendationJob {

    private static final Logger log = LoggerFactory.getLogger(DailyRecommendationJob.class);

    private final DailyRecommendationService service;
    private final List<Long> userIds;

    public DailyRecommendationJob(
            DailyRecommendationService service,
            @Value("${recommendation.schedule.user-ids:}") String userIdsCsv) {
        this.service = service;
        this.userIds = parseUserIds(userIdsCsv);
    }

    /** 盘前预热入口（@Scheduled 每日 09:00，可配）。 */
    @Scheduled(cron = "${recommendation.schedule.cron:0 0 9 * * ?}")
    public void prefetchDaily() {
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
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
