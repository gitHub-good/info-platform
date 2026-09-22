package com.info.platform.application.jobrun;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 任务注册表（T37，方案 §4.5）：收集容器内全部 {@link ManagedJob}，供调度中心注册、任务中心总览与手动触发检索。
 *
 * <p>注册即权威：实现本接口并注册为 Spring bean 即被收编（无需改调度代码）；jobKey 冲突在启动期 fail-fast（同名键 会互相覆盖调度配置，属装配错误）。迭代顺序为
 * Spring 注入顺序（稳定），供页面按固定顺序展示。
 */
@Component
public class JobRegistry {

    private final Map<String, ManagedJob> jobsByKey;

    public JobRegistry(List<ManagedJob> jobs) {
        Map<String, ManagedJob> map = new LinkedHashMap<>();
        for (ManagedJob job : jobs) {
            ManagedJob previous = map.putIfAbsent(job.jobKey(), job);
            if (previous != null) {
                throw new IllegalStateException(
                        "ManagedJob jobKey 冲突: "
                                + job.jobKey()
                                + "（"
                                + previous.getClass().getName()
                                + " 与 "
                                + job.getClass().getName()
                                + "）");
            }
        }
        this.jobsByKey = Collections.unmodifiableMap(map);
    }

    /** 全部受管任务（注册顺序）。 */
    public List<ManagedJob> jobs() {
        return List.copyOf(jobsByKey.values());
    }

    /** 按 jobKey 检索（REST 路径参数解析用）。 */
    public Optional<ManagedJob> byJobKey(String jobKey) {
        return Optional.ofNullable(jobsByKey.get(jobKey));
    }
}
