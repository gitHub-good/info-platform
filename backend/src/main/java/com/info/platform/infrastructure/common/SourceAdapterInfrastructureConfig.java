package com.info.platform.infrastructure.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.infrastructure.aggregation.FieldMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源 adapter 框架的共享基础设施 Bean（T02）：Caffeine 缓存、字段映射器、弹性执行器、熔断器占位。
 *
 * <p>具体各源 adapter（T03~T08，T36 起由 {@code SourceAdapterRoutingConfig} 注册为内部 bean）注入这些 Bean
 * 即可，无需各自重复装配。弹性选型见 ADR-0010。
 */
@Configuration
public class SourceAdapterInfrastructureConfig {

    /** 数据源缓存（T36 TTL 热化）：每条目写入时从 {@code datasource.{CODE}.cacheTtlSeconds} 解析（LIVE 级）， 容量启动期固化。 */
    @Bean
    public SourceCache sourceCache(ConfigCenter configCenter) {
        return new SourceCache(code -> configCenter.dataSource(code).cacheTtl());
    }

    @Bean
    public FieldMapper fieldMapper(ObjectMapper objectMapper) {
        return new FieldMapper(objectMapper);
    }

    /** 弹性执行器用虚拟线程（Java 21，daemon、轻量），承载阻塞式外部调用；context 关闭时 shutdown。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService sourceResilienceExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("source-resilience-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Bean
    public ResilienceRunner resilienceRunner(ExecutorService sourceResilienceExecutor) {
        return new ResilienceRunner(sourceResilienceExecutor);
    }

    /**
     * 聚合编排并行执行器（虚拟线程），供 {@code AggregationService} 并行调度各 SourceAdapter。
     *
     * <p>与 {@link #sourceResilienceExecutor} 分离：前者承载各 adapter 内部阻塞式 doFetch（含超时）， 本执行器承载应用层
     * CompletableFuture 并行编排。均为 thread-per-task 虚拟线程，无池上限，轻量。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService aggregationExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("aggregation-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Bean
    public CircuitBreaker circuitBreaker() {
        return new NoopCircuitBreaker();
    }
}
