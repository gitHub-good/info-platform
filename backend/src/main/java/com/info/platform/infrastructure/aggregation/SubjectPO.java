package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.Map;

/**
 * subject_master 表的持久化对象（PO）。
 *
 * <p>autoResultMap=true 让 {@link JacksonTypeHandler} 在 SELECT 时生效； external_codes（JSON）经
 * TypeHandler 自动序列化/反序列化。 market/subject_type/status 用原始字符串/整型存储，领域枚举在仓储实现层转换。
 */
@TableName(value = "subject_master", autoResultMap = true)
public class SubjectPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_code")
    private String subjectCode;

    @TableField("market")
    private String market;

    @TableField("subject_type")
    private Integer subjectType;

    @TableField("name")
    private String name;

    @TableField(value = "external_codes", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> externalCodes;

    @TableField("industry")
    private String industry;

    @TableField("status")
    private Integer status;

    /** 外部消失连续缺失计数（V18 列 / ADR-0028；同步引擎机制列）。 */
    @TableField("missing_streak")
    private Integer missingStreak;

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

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Integer getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(Integer subjectType) {
        this.subjectType = subjectType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getExternalCodes() {
        return externalCodes;
    }

    public void setExternalCodes(Map<String, String> externalCodes) {
        this.externalCodes = externalCodes;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMissingStreak() {
        return missingStreak;
    }

    public void setMissingStreak(Integer missingStreak) {
        this.missingStreak = missingStreak;
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
