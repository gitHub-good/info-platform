package com.info.platform.domain.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link RuntimeConfig} 实体测试（T34）：构造校验与写时整体替换语义。 */
class RuntimeConfigTest {

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    void create_setsBothTimestampsToNow() {
        // Act
        RuntimeConfig config = RuntimeConfig.create("llm.global", "{}", "说明", T1);

        // Assert
        assertThat(config.getConfigKey()).isEqualTo("llm.global");
        assertThat(config.getConfigValue()).isEqualTo("{}");
        assertThat(config.getDescription()).isEqualTo("说明");
        assertThat(config.getCreatedAt()).isEqualTo(T1);
        assertThat(config.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void create_blankKeyOrValue_rejected() {
        // Assert：键空白 / 含空格 / 值空白 → 构造期拒绝（fail fast）
        assertThatThrownBy(() -> RuntimeConfig.create(" ", "{}", null, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuntimeConfig.create("llm global", "{}", null, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuntimeConfig.create("llm.global", " ", null, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withUpdatedValue_replacesValueOnly_keepsKeyDescriptionCreatedAt() {
        // Arrange
        RuntimeConfig original = RuntimeConfig.create("llm.global", "{\"a\":1}", "说明", T1);

        // Act
        RuntimeConfig updated = original.withUpdatedValue("{\"a\":2}", T2);

        // Assert：新实例换值与 updated_at；原实例不变（不可变实体）
        assertThat(updated.getConfigValue()).isEqualTo("{\"a\":2}");
        assertThat(updated.getUpdatedAt()).isEqualTo(T2);
        assertThat(updated.getCreatedAt()).isEqualTo(T1);
        assertThat(updated.getDescription()).isEqualTo("说明");
        assertThat(original.getConfigValue()).isEqualTo("{\"a\":1}");
        assertThat(original.getUpdatedAt()).isEqualTo(T1);
    }
}
