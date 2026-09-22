package com.info.platform.application.jobrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** JobRegistry 单测（T37）：注册收集 / jobKey 检索 / 冲突 fail-fast。AAA 结构（纯 JDK，不依赖 Spring）。 */
class JobRegistryTest {

    private static ManagedJob stub(String jobKey) {
        return new ManagedJob() {
            @Override
            public String jobKey() {
                return jobKey;
            }

            @Override
            public String displayName() {
                return jobKey;
            }

            @Override
            public String description() {
                return jobKey;
            }

            @Override
            public ScheduleType scheduleType() {
                return ScheduleType.FIXED_DELAY;
            }

            @Override
            public void run() {}
        };
    }

    @Test
    void jobs_registeredInOrder() {
        JobRegistry registry = new JobRegistry(List.of(stub("A"), stub("B")));

        assertThat(registry.jobs()).extracting(ManagedJob::jobKey).containsExactly("A", "B");
        assertThat(registry.byJobKey("A")).isPresent();
        assertThat(registry.byJobKey("B")).isPresent();
        assertThat(registry.byJobKey("MISSING")).isEmpty();
    }

    @Test
    void duplicateJobKey_failsFastAtConstruction() {
        // Arrange + Act + Assert：同名键会互相覆盖调度配置，属装配错误，启动期即抛
        assertThatThrownBy(() -> new JobRegistry(List.of(stub("A"), stub("A"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jobKey 冲突");
    }

    @Test
    void emptyRegistry_isEmpty() {
        JobRegistry registry = new JobRegistry(List.of());

        assertThat(registry.jobs()).isEmpty();
        assertThat(registry.byJobKey("ANY")).isEmpty();
    }
}
