package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * push_record 表的持久化对象（PO）。
 *
 * <p>对齐 V6
 * 迁移：id/user_id/subject_id/push_type/ref_id/content/idempotency_key/status/pushed_at/retry_count/created_at/
 * updated_at/version。 {@code UNIQUE(idempotency_key)} 为防重推 DB 层最后防线； {@code
 * idx_push_user_status(user_id, status)} 支撑 history 游标分页与重连补拉查询。
 *
 * <p>{@code push_type TINYINT} / {@code status TINYINT} 用 {@link Integer} 承载； {@code subject_id} /
 * {@code ref_id} 可空 （非异动类推送未必绑定标的/事件）； 时间戳存 ISO-8601 文本； {@code version} 带 {@link Version}
 * 乐观锁（push_record 存在并发状态翻转）。
 */
@TableName("push_record")
public class PushRecordPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("push_type")
    private Integer pushType;

    @TableField("ref_id")
    private String refId;

    @TableField("content")
    private String content;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("status")
    private Integer status;

    @TableField("pushed_at")
    private String pushedAt;

    @TableField("retry_count")
    private Integer retryCount;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Integer getPushType() {
        return pushType;
    }

    public void setPushType(Integer pushType) {
        this.pushType = pushType;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getPushedAt() {
        return pushedAt;
    }

    public void setPushedAt(String pushedAt) {
        this.pushedAt = pushedAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
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
