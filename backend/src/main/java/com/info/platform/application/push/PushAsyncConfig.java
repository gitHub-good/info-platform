package com.info.platform.application.push;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 推送异步执行器配置（应用层横切）。
 *
 * <p>为 {@link PushService#onAnomalyDetected} 的 {@code @Async("pushAsyncExecutor")} 提供命名执行器：Java 21
 * 虚拟线程每任务一个 线程（{@link VirtualThreadTaskExecutor}），推送任务轻量 IO（写 push_record + SSE 发送）适合虚拟线程承载，不占平台线程。
 * 对齐后端基线「Spring ApplicationEvent+@Async 进程内异步」（轻量级基线，ADR-0001/0006）。
 *
 * <p>{@link EnableAsync} 仅开启 {@code @Async} 代理，不影响任务调度（T37 后调度由 JobScheduler 集中管理， 按运行时配置启停）。 测试
 * profile 不关本配置——{@code @Async} 仅对带注解方法生效，无事件触发即空转。
 */
@Configuration
@EnableAsync
public class PushAsyncConfig {

    @Bean(name = "pushAsyncExecutor")
    public AsyncTaskExecutor pushAsyncExecutor() {
        return new VirtualThreadTaskExecutor("push-async-");
    }
}
