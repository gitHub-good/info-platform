package com.info.platform.infrastructure.push;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 心跳调度器配置（基础设施层横切，系统体检 20260924 O8）。
 *
 * <p>为 {@link SseConnectionRegistry} 的周期 comment 心跳帧提供单线程守护调度器（个人量级单实例足够）；守护线程不阻 JVM 退出。测试 profile
 * 同样装配——注册表单测注入受控调度器绕开真实等待。
 */
@Configuration
public class SseHeartbeatConfig {

    @Bean(name = "sseHeartbeatScheduler")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "sse-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
