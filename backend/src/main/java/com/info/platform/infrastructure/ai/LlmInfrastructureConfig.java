package com.info.platform.infrastructure.ai;

import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeLlmGlobal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 网关基础设施 Bean 装配（T19；T35 热改：改以 {@link ConfigCenter} 运行时视图装配，ADR-0017 §4.3）。
 *
 * <p>{@link LlmCache}/{@link LlmCostGuard} 为纯类（不 {@code @Component}），在此装配为 Bean； TTL
 * 分档与预算/告警阈值均注入<b>用时读取</b>的运行时函数（保存即生效，新缓存条目/下一次调用生效）—— maximumSize 与异常兜底默认 TTL 仍取启动期 yml
 * 值（Caffeine 容量建后不可变 + 兜底仅理论可达）。 {@code llmExecutor} 为虚拟线程执行器，承载 {@code LlmGatewayImpl} 与连通性测试的
 * Future 超时包装（ADR-0010）。
 */
@Configuration
public class LlmInfrastructureConfig {

    @Bean
    LlmCache llmCache(ConfigCenter configCenter, LlmConfig config) {
        return new LlmCache(
                briefType ->
                        configCenter
                                .llmGlobal()
                                .map(global -> global.ttlFor(briefType))
                                .orElseGet(() -> config.getCache().ttlFor(briefType)),
                Duration.ofSeconds(config.getCache().getDefaultTtlSeconds()),
                config.getCache().getMaximumSize());
    }

    @Bean
    LlmCostGuard llmCostGuard(ConfigCenter configCenter, LlmConfig config) {
        // T30：时钟注入（日界键严格跨日重置，测试可注入固定时钟）；T35：预算/阈值改运行时读取
        return new LlmCostGuard(runtimeGlobal(configCenter, config), Clock.systemDefaultZone());
    }

    /** LLM 调用虚拟线程执行器（thread-per-task、daemon、轻量），承载阻塞式外部 HTTP 调用；context 关闭时 shutdown。 */
    @Bean(destroyMethod = "shutdown")
    ExecutorService llmExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("llm-call-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    /** 运行时全局视图供应：快照缺键（种子前/未写入）回落 yml 绑定值，消费点永不因缺键失效。 */
    static Supplier<RuntimeLlmGlobal> runtimeGlobal(ConfigCenter configCenter, LlmConfig config) {
        return () ->
                configCenter
                        .llmGlobal()
                        .orElseGet(
                                () ->
                                        new RuntimeLlmGlobal(
                                                config.getTimeoutSeconds(),
                                                config.getRetry(),
                                                config.getDailyTokenBudgetPerUser(),
                                                config.getBudgetWarnRatio(),
                                                config.getCache().getDefaultTtlSeconds(),
                                                config.getCache().getTtl()));
    }
}
