package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * reading_event 表的持久化对象（T29）。
 *
 * <p>对齐 V14 迁移：id/user_id/subject_id/content_type/content_ref/created_at。无 version 列 （追加型流水，只
 * INSERT），不标 {@code @Version}（对齐 llm_call_log 模式）。
 *
 * <p>{@code content_type TEXT} 存枚举名（SUBJECT_DETAIL/POLICY/AI_BRIEF）；时间戳存 ISO-8601 整秒
 * 文本（字典序即时间序）；user_id 用 {@link Integer} 承载（SQLite INTEGER 亲和，个人量级用户数不溢出 int）。
 */
@TableName("reading_event")
public class ReadingEventPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("content_type")
    private String contentType;

    @TableField("content_ref")
    private String contentRef;

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

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentRef() {
        return contentRef;
    }

    public void setContentRef(String contentRef) {
        this.contentRef = contentRef;
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
