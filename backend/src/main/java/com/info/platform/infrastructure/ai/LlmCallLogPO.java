package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * llm_call_log 表的持久化对象（T30）。
 *
 * <p>对齐 V13
 * 迁移：id/user_id/provider/model/scene_key/status/cache_hit/prompt_tokens/completion_tokens/
 * cost_micros/duration_millis/error_message/created_at。无 version 列（追加型流水，只 INSERT），不标
 * {@code @Version}，{@code OptimisticLockerInnerInterceptor} 仅对带 {@code @Version} 的 PO 生效，本 PO 不受影响。
 *
 * <p>{@code status TEXT} 存枚举名（SUCCESS/FAILED/REJECTED）；时间戳存 ISO-8601 整秒文本（字典序即时间序）； token/耗时/成本用
 * {@link Integer} 承载（SQLite INTEGER 亲和，个人量级 token 数与微元成本均不溢出 int）。
 */
@TableName("llm_call_log")
public class LlmCallLogPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("provider")
    private String provider;

    @TableField("model")
    private String model;

    @TableField("scene_key")
    private String sceneKey;

    @TableField("status")
    private String status;

    @TableField("cache_hit")
    private Integer cacheHit;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("cost_micros")
    private Integer costMicros;

    @TableField("duration_millis")
    private Integer durationMillis;

    @TableField("error_message")
    private String errorMessage;

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

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSceneKey() {
        return sceneKey;
    }

    public void setSceneKey(String sceneKey) {
        this.sceneKey = sceneKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Integer cacheHit) {
        this.cacheHit = cacheHit;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getCostMicros() {
        return costMicros;
    }

    public void setCostMicros(Integer costMicros) {
        this.costMicros = costMicros;
    }

    public Integer getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Integer durationMillis) {
        this.durationMillis = durationMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
