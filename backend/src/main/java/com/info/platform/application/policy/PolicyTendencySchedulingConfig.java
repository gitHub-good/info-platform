package com.info.platform.application.policy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 政策倾向判断调度开关（应用层横切，对齐 {@link com.info.platform.application.ai.RecommendationSchedulingConfig} 与
 * {@link com.info.platform.application.push.AnomalySchedulingConfig} 模式）。
 *
 * <p>{@link EnableScheduling} 与 {@link PolicyTendencyJob} 同受 {@code policy.tendency.enabled} 开关约束：
 * 生产置 {@code true} 开启 @Scheduled 批量倾向判断；测试 profile / 默认 {@code false}，调度基础设施与 Job 均不装配—— @Scheduled
 * 不在 {@code @SpringBootTest} 触发，倾向逻辑改由单测直调 {@link PolicyTendencyService#judgeTendency} 验证 （对齐 04
 * 测试规范）。
 *
 * <p>独立于政策抓取调度开关（{@code policy.fetch.enabled}）：倾向判断可单独启停，不与抓取耦合（抓取入库后倾向 Job 下一轮扫到 ai_tendency=0
 * 的条目再判断）。
 */
@Configuration
@ConditionalOnProperty(name = "policy.tendency.enabled", havingValue = "true")
@EnableScheduling
public class PolicyTendencySchedulingConfig {}
