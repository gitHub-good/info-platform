package com.info.platform.application.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 异动检测调度开关（应用层横切）。
 *
 * <p>{@link EnableScheduling} 与 {@link AnomalyDetectionJob} 同受 {@code anomaly.detect.enabled} 开关约束：
 * 生产置 {@code true} 开启 @Scheduled 轮询；测试 profile 置 {@code false}，调度基础设施与 Job 均不装配 ——
 * {@code @Scheduled} 不会在 {@code @SpringBootTest} 中触发，单测改走直接调 {@code detectForItem} 验证逻辑（对齐 04
 * 测试规范）。 偏离默认「@EnableScheduling 放主入口」的做法是有意为之：把调度副作用隔离在异动特性内，避免污染全部集成测试上下文。
 */
@Configuration
@ConditionalOnProperty(name = "anomaly.detect.enabled", havingValue = "true")
@EnableScheduling
public class AnomalySchedulingConfig {}
