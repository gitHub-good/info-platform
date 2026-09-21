package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * ai_brief 表的持久化对象（PO，对齐 V9 迁移）。
 *
 * <p>{@code brief_type}/{@code status}/{@code cost_tokens} 为 {@code TINYINT}/{@code INTEGER}，用
 * {@link Integer} 承载； {@code content}/{@code source_links} 为 JSON 文本（由应用层 {@code
 * BriefContentParser} 产出字符串落库，PO 不做 JSON 解析，避免 TypeHandler 与领域 {@link
 * com.info.platform.domain.ai.BriefContent} 耦合）；时间戳存 ISO-8601 整秒文本。
 *
 * <p>{@link #version} 标 {@code @Version}，使 {@code OptimisticLockerInnerInterceptor}（见 {@code
 * MyBatisPlusConfig}）在 {@code updateById} 时自动追加 {@code WHERE version=?} 并 bump——守护终态写入的乐观锁（CAS 领取另走
 * {@code AiBriefRepositoryImpl#claim} 的显式 update）。
 */
@TableName("ai_brief")
public class AiBriefPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("brief_type")
    private Integer briefType;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("model")
    private String model;

    @TableField("content")
    private String content;

    @TableField("source_links")
    private String sourceLinks;

    @TableField("cost_tokens")
    private Integer costTokens;

    @TableField("status")
    private Integer status;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("created_at")
    private String createdAt;

    @TableField("updated_at")
    private String updatedAt;

    @Version
    @TableField("version")
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Integer getBriefType() {
        return briefType;
    }

    public void setBriefType(Integer briefType) {
        this.briefType = briefType;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceLinks() {
        return sourceLinks;
    }

    public void setSourceLinks(String sourceLinks) {
        this.sourceLinks = sourceLinks;
    }

    public Integer getCostTokens() {
        return costTokens;
    }

    public void setCostTokens(Integer costTokens) {
        this.costTokens = costTokens;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
