package com.info.platform.domain.common;

import java.time.Instant;
import java.util.Objects;

/**
 * 运行时配置实体（runtime_config 表，T34 / ADR-0017）。
 *
 * <p>单表 key-value：{@code configKey} 域前缀命名（llm. / datasource. / job. / aggregation.），{@code
 * configValue} 存 JSON 文档（结构由各域校验器约束）。不可变实体：更新经 {@link #withUpdatedValue} 生成新实例，供内存快照写时整体替换
 * （快照不可变、volatile 引用替换）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型；JSON 的解析与校验在应用层/基础设施层完成。
 */
public final class RuntimeConfig {

    private final String configKey;
    private final String configValue;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;

    private RuntimeConfig(
            String configKey,
            String configValue,
            String description,
            Instant createdAt,
            Instant updatedAt) {
        if (configKey == null || configKey.isBlank() || configKey.chars().anyMatch(c -> c == ' ')) {
            throw new IllegalArgumentException("configKey 必填且不含空白字符: " + configKey);
        }
        if (configValue == null || configValue.isBlank()) {
            throw new IllegalArgumentException("configValue 必填（JSON 文档）: " + configKey);
        }
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 构建起始记录（首次写入 / 种子导入）：created_at = updated_at = now。
     *
     * @param configKey 域前缀键（如 "llm.global"）
     * @param configValue JSON 文档文本
     * @param description 人读说明（可空）
     * @param now 写入时刻
     */
    public static RuntimeConfig create(
            String configKey, String configValue, String description, Instant now) {
        Objects.requireNonNull(now, "now 必填");
        return new RuntimeConfig(configKey, configValue, description, now, now);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static RuntimeConfig reconstruct(
            String configKey,
            String configValue,
            String description,
            Instant createdAt,
            Instant updatedAt) {
        Objects.requireNonNull(createdAt, "createdAt 必填");
        Objects.requireNonNull(updatedAt, "updatedAt 必填");
        return new RuntimeConfig(configKey, configValue, description, createdAt, updatedAt);
    }

    /** 写时整体替换：仅换值与 updated_at，键/说明/创建时间保持不变（返回新实例，本实例不变）。 */
    public RuntimeConfig withUpdatedValue(String newConfigValue, Instant at) {
        Objects.requireNonNull(at, "at 必填");
        return new RuntimeConfig(configKey, newConfigValue, description, createdAt, at);
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
