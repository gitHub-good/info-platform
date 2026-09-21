package com.info.platform.application.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 每日推荐调度开关（应用层横切，对齐 {@link com.info.platform.application.push.AnomalySchedulingConfig} 模式）。
 *
 * <p>{@link EnableScheduling} 与 {@link DailyRecommendationJob} 同受 {@code
 * recommendation.schedule.enabled} 开关约束： 生产置 {@code true} 开启盘前 @Scheduled 预热；测试 profile / 默认 {@code
 * false}，调度基础设施与 Job 均不装配 —— @Scheduled 不在 {@code @SpringBootTest} 触发，每日推荐逻辑改由单测直调 {@link
 * DailyRecommendationService#generateDaily} 验证（对齐 04 测试规范）。
 *
 * <p>独立于异动调度开关（{@code anomaly.detect.enabled}）：每日推荐预热可单独启停，不与异动检测耦合。
 */
@Configuration
@ConditionalOnProperty(name = "recommendation.schedule.enabled", havingValue = "true")
@EnableScheduling
public class RecommendationSchedulingConfig {}
