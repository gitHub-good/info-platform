package com.info.platform.infrastructure.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 网关基础设施 Bean 装配（T19，对齐 {@code SourceAdapterInfrastructureConfig} 模式）。
 *
 * <p>{@link LlmCache}/{@link LlmCostGuard} 为纯类（不 {@code @Component}），在此按 {@link LlmConfig} 装配为
 * Bean； {@code llmExecutor} 为虚拟线程执行器，承载 {@code LlmGatewayImpl} 的 Future 超时包装（ADR-0010）。
 * Adapter（{@link DeepSeekAdapter}/{@link GlmAdapter}）与 {@link LlmGatewayImpl}/{@link LlmConfig} 各自
 * {@code @Component} 装配。
 */
@Configuration
public class LlmInfrastructureConfig {

    @Bean
    LlmCache llmCache(LlmConfig config) {
        LlmConfig.Cache cacheCfg = config.getCache();
        return new LlmCache(
                cacheCfg::ttlFor,
                Duration.ofSeconds(cacheCfg.getDefaultTtlSeconds()),
                cacheCfg.getMaximumSize());
    }

    @Bean
    LlmCostGuard llmCostGuard(LlmConfig config) {
        // T30：预算 + 告警阈值 + 时钟注入（日界键严格跨日重置，测试可注入固定时钟）
        return new LlmCostGuard(
                config.getDailyTokenBudgetPerUser(),
                config.getBudgetWarnRatio(),
                Clock.systemDefaultZone());
    }

    /** LLM 调用虚拟线程执行器（thread-per-task、daemon、轻量），承载阻塞式外部 HTTP 调用；context 关闭时 shutdown。 */
    @Bean(destroyMethod = "shutdown")
    ExecutorService llmExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("llm-call-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
