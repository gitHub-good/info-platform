package com.info.platform.infrastructure.policy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.List;

/**
 * policy_item 表的持久化对象（PO）。
 *
 * <p>对齐 V10
 * 迁移：id/title/source/published_at/summary/related_industries/ai_tendency/source_url/created_at/updated_at；V16
 * 增补尝试留痕列 tendency_attempts/tendency_last_attempt_at（P0-3 重试上限与退避过滤键，重试治理元数据不进领域实体）。
 * 按 §4.2 DDL 无 version 列（追加型政策流，无并发 UPDATE 竞争，同 anomaly_event），故不标 {@code @Version}。
 *
 * <p>{@code related_industries}（JSON 数组）经 {@link JacksonTypeHandler} 自动序列化/反序列化； {@code
 * autoResultMap=true} 让 TypeHandler 在 SELECT 时生效（同 {@code SubjectPO#externalCodes}）。 {@code
 * published_at} 存 ISO-8601 日期文本 （yyyy-MM-dd），PO 用 String 承载，领域 {@link
 * com.info.platform.domain.policy.PolicyItem} 用 {@link java.time.LocalDate}； {@code ai_tendency
 * TINYINT} 用 {@link Integer} 承载。
 */
@TableName(value = "policy_item", autoResultMap = true)
public class PolicyItemPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("source")
    private String source;

    @TableField("published_at")
    private String publishedAt;

    @TableField("summary")
    private String summary;

    @TableField(value = "related_industries", typeHandler = JacksonTypeHandler.class)
    private List<String> relatedIndustries;

    @TableField("ai_tendency")
    private Integer aiTendency;

    /** V16（P0-3）：判断尝试次数（NOT NULL DEFAULT 0，重试上限过滤键）。 */
    @TableField("tendency_attempts")
    private Integer tendencyAttempts;

    /** V16（P0-3）：上次尝试时间（ISO-8601 整秒文本，NULL=从未尝试，退避窗过滤键）。 */
    @TableField("tendency_last_attempt_at")
    private String tendencyLastAttemptAt;

    @TableField("source_url")
    private String sourceUrl;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getRelatedIndustries() {
        return relatedIndustries;
    }

    public void setRelatedIndustries(List<String> relatedIndustries) {
        this.relatedIndustries = relatedIndustries;
    }

    public Integer getAiTendency() {
        return aiTendency;
    }

    public void setAiTendency(Integer aiTendency) {
        this.aiTendency = aiTendency;
    }

    public Integer getTendencyAttempts() {
        return tendencyAttempts;
    }

    public void setTendencyAttempts(Integer tendencyAttempts) {
        this.tendencyAttempts = tendencyAttempts;
    }

    public String getTendencyLastAttemptAt() {
        return tendencyLastAttemptAt;
    }

    public void setTendencyLastAttemptAt(String tendencyLastAttemptAt) {
        this.tendencyLastAttemptAt = tendencyLastAttemptAt;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
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
