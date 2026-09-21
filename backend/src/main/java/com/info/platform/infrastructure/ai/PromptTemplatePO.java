package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * prompt_template 表的持久化对象（PO）。
 *
 * <p>对齐 V8 迁移：id/brief_type/version/template/status/created_at/updated_at。 按 §4.2 DDL 无 version
 * 乐观锁列 （低频配置表，编辑即新版本行，无并发 UPDATE 竞争）， 故不标 {@code @Version}，{@code OptimisticLockerInnerInterceptor}
 * 仅对带 {@code @Version} 的 PO 生效，本 PO 不受其影响。
 *
 * <p>{@code brief_type}/{@code status} 为 {@code TINYINT}，用 {@link Integer} 承载； 时间戳存 ISO-8601 整秒文本。
 */
@TableName("prompt_template")
public class PromptTemplatePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("brief_type")
    private Integer briefType;

    @TableField("version")
    private String version;

    @TableField("template")
    private String template;

    @TableField("status")
    private Integer status;

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

    public Integer getBriefType() {
        return briefType;
    }

    public void setBriefType(Integer briefType) {
        this.briefType = briefType;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
