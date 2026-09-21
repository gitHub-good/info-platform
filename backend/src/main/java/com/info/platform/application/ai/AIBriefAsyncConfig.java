package com.info.platform.application.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * AI 简报异步执行器配置（应用层横切，T21）。
 *
 * <p>为 {@link AIBriefService#onGenerationRequested} 的 {@code @Async("aiBriefAsyncExecutor")}
 * 提供命名执行器：Java 21 虚拟线程每任务一个（{@link VirtualThreadTaskExecutor}），AI 生成阻塞 IO（LLM 调用 30s +
 * 数据源取数）适合虚拟线程承载， 不占平台线程。对齐后端基线「Spring ApplicationEvent+@Async 进程内异步」（轻量级基线，ADR-0001/0006）。
 *
 * <p>{@code @EnableAsync} 已由 {@code PushAsyncConfig}（T14）开启，本配置仅追加执行器 Bean，不重复声明开关。
 */
@Configuration
public class AIBriefAsyncConfig {

    @Bean(name = "aiBriefAsyncExecutor")
    public AsyncTaskExecutor aiBriefAsyncExecutor() {
        return new VirtualThreadTaskExecutor("ai-brief-async-");
    }
}
