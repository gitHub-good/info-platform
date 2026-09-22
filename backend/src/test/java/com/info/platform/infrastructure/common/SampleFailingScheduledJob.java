package com.info.platform.infrastructure.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * JobExecutionAspect 织入测试夹具：抛异常的样本 Job（@Component + @Scheduled，与真实 Job 同形态）。
 *
 * <p>仅测试显式调 run() 触发 FAILED 记录（@EnableScheduling 未装配，不自动触发）。bean 创建不调用 run()， 不影响其他测试。
 */
@Component
public class SampleFailingScheduledJob {

    /** 抛异常以验证 @Around 捕获 → 记 FAILED + 原样上抛。 */
    @Scheduled(fixedDelay = 999999999L)
    public void run() {
        throw new IllegalStateException("sample-failure");
    }
}
