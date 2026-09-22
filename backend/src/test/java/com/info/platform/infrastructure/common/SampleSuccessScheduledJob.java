package com.info.platform.infrastructure.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * JobExecutionAspect 织入测试夹具：正常返回的样本 Job（@Component + @Scheduled，与真实 Job 同形态）。
 *
 * <p>仅本测试包可见、被组件扫描拾取（测试 profile 无 @EnableScheduling，调度器不自动触发 run()， 仅测试显式调用验证 AOP 记录）。 run() 无副作用。
 */
@Component
public class SampleSuccessScheduledJob {

    /** 正常完成；jobName 派生自本类简单名。 */
    @Scheduled(fixedDelay = 999999999L)
    public void run() {
        // 正常完成
    }
}
