package com.info.platform.infrastructure.feed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * info_source 表的持久化对象（M13 T100，V22）。
 *
 * <p>{@code config} 为 JSON 文档文本（经 {@link SourceConfigCodec} 与领域 {@code SourceConfig} 互转）； {@code
 * enabled} /{@code is_preset}/{@code deleted} 为 TINYINT 0/1；时间戳整秒 ISO-8601 UTC 文本。
 */
@TableName("info_source")
public class InfoSourcePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("source_code")
    private String sourceCode;

    @TableField("name")
    private String name;

    @TableField("category")
    private String category;

    @TableField("adapter_type")
    private String adapterType;

    @TableField("adapter_ref")
    private String adapterRef;

    @TableField("endpoint")
    private String endpoint;

    @TableField("config")
    private String config;

    @TableField("interval_minutes")
    private Integer intervalMinutes;

    @TableField("enabled")
    private Integer enabled;

    @TableField("is_preset")
    private Integer isPreset;

    @TableField("deleted")
    private Integer deleted;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public void setAdapterType(String adapterType) {
        this.adapterType = adapterType;
    }

    public String getAdapterRef() {
        return adapterRef;
    }

    public void setAdapterRef(String adapterRef) {
        this.adapterRef = adapterRef;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getIsPreset() {
        return isPreset;
    }

    public void setIsPreset(Integer isPreset) {
        this.isPreset = isPreset;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
