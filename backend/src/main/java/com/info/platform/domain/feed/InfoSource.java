package com.info.platform.domain.feed;

import java.time.Instant;
import java.util.Objects;

/**
 * 资讯源实体（{@code info_source} 表，M13 T100，ADR-0038）。
 *
 * <p>预置源与用户通用源统一行模型；频控红线（{@code interval_minutes} 1~60）在构造期把守（实体即规则的单一事实源，
 * 页面/种子/引擎三处共用）；结构级 config 校验（映射/headers/游标声明）归应用层 {@code SourceConfigValidator}。
 *
 * <p>生命周期：预置源无删除入口只有启停与参数编辑（蓝图裁决 1）；通用源删除 = 停用 + 软删（历史条目保留）。
 */
public class InfoSource {

    /** 频控下限（分钟）：礼貌抓取红线，低于 1 分钟拒绝（方案 §5）。 */
    public static final int MIN_INTERVAL_MINUTES = 1;

    /** 频控上限（分钟）。 */
    public static final int MAX_INTERVAL_MINUTES = 60;

    private Long id;
    private final String sourceCode;
    private String name;
    private String category;
    private final AdapterType adapterType;
    private final String adapterRef;
    private String endpoint;
    private SourceConfig config;
    private int intervalMinutes;
    private boolean enabled;
    private final boolean preset;
    private boolean deleted;
    private Instant createdAt;
    private Instant updatedAt;

    private InfoSource(
            Long id,
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            SourceConfig config,
            int intervalMinutes,
            boolean enabled,
            boolean preset,
            boolean deleted,
            Instant createdAt,
            Instant updatedAt) {
        this.sourceCode = requireText(sourceCode, "sourceCode");
        this.name = requireText(name, "name");
        this.category = requireText(category, "category");
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType 必填");
        this.adapterRef = adapterRef;
        this.endpoint = requireText(endpoint, "endpoint");
        this.config = config == null ? SourceConfig.empty() : config;
        if (intervalMinutes < MIN_INTERVAL_MINUTES || intervalMinutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                    "intervalMinutes 须在 "
                            + MIN_INTERVAL_MINUTES
                            + "~"
                            + MAX_INTERVAL_MINUTES
                            + " 分钟内: "
                            + intervalMinutes);
        }
        this.intervalMinutes = intervalMinutes;
        if (adapterType == AdapterType.PRESET && (adapterRef == null || adapterRef.isBlank())) {
            throw new IllegalArgumentException("preset 源必须声明 adapter_ref（Spring bean 名）");
        }
        if (adapterType != AdapterType.PRESET && adapterRef != null && !adapterRef.isBlank()) {
            throw new IllegalArgumentException("仅 preset 源可声明 adapter_ref");
        }
        this.enabled = enabled;
        this.preset = preset;
        this.deleted = deleted;
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 新建源（id/时间戳落库回填）。
     *
     * @param preset true = 预置源（种子/目录）；false = 页面自增通用源
     */
    public static InfoSource create(
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            SourceConfig config,
            int intervalMinutes,
            boolean enabled,
            boolean preset) {
        return new InfoSource(
                null, sourceCode, name, category, adapterType, adapterRef, endpoint, config,
                intervalMinutes, enabled, preset, false, null, null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static InfoSource reconstruct(
            Long id,
            String sourceCode,
            String name,
            String category,
            AdapterType adapterType,
            String adapterRef,
            String endpoint,
            SourceConfig config,
            int intervalMinutes,
            boolean enabled,
            boolean preset,
            boolean deleted,
            Instant createdAt,
            Instant updatedAt) {
        return new InfoSource(
                id, sourceCode, name, category, adapterType, adapterRef, endpoint, config,
                intervalMinutes, enabled, preset, deleted, createdAt, updatedAt);
    }

    /** 编辑可变字段（name/category/endpoint/config/interval/enabled，PATCH 下一 tick 生效）。 */
    public void edit(
            String name,
            String category,
            String endpoint,
            SourceConfig config,
            Integer intervalMinutes,
            Boolean enabled) {
        if (name != null) {
            this.name = requireText(name, "name");
        }
        if (category != null) {
            this.category = requireText(category, "category");
        }
        if (endpoint != null) {
            this.endpoint = requireText(endpoint, "endpoint");
        }
        if (config != null) {
            this.config = config;
        }
        if (intervalMinutes != null) {
            if (intervalMinutes < MIN_INTERVAL_MINUTES
                    || intervalMinutes > MAX_INTERVAL_MINUTES) {
                throw new IllegalArgumentException(
                        "intervalMinutes 须在 "
                                + MIN_INTERVAL_MINUTES
                                + "~"
                                + MAX_INTERVAL_MINUTES
                                + " 分钟内: "
                                + intervalMinutes);
            }
            this.intervalMinutes = intervalMinutes;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
    }

    /** 启停切换（调度摘除/恢复，下一 tick 生效）。 */
    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 软删（仅通用源；预置源由应用层拦截 30073，此方法不设防以保持实体纯粹）。 */
    public void markDeleted() {
        this.deleted = true;
        this.enabled = false;
    }

    /** 恢复软删源（页面「恢复」交互：配置归档可逆）。 */
    public void restore() {
        this.deleted = false;
    }

    public Long getId() {
        return id;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public AdapterType getAdapterType() {
        return adapterType;
    }

    public String getAdapterRef() {
        return adapterRef;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public SourceConfig getConfig() {
        return config;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPreset() {
        return preset;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 落库回填（id/时间戳由仓储填充；insertIfAbsent 回查后回填自增 id）。 */
    public void assignPersisted(Long id, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
