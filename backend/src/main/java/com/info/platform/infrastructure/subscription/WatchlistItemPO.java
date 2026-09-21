package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;

/**
 * watchlist_item 表的持久化对象（PO）。
 *
 * <p>对齐 V4 迁移：id/watchlist_id/subject_id/anomaly_threshold/status/created_at/updated_at/version， {@code
 * UNIQUE(watchlist_id, subject_id)} 为加标的幂等 DB 层最后防线。 {@code anomaly_threshold DECIMAL(5,2)} 映射
 * {@link BigDecimal} 避免浮点精度损失。
 */
@TableName("watchlist_item")
public class WatchlistItemPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("watchlist_id")
    private Long watchlistId;

    @TableField("subject_id")
    private Long subjectId;

    @TableField("anomaly_threshold")
    private BigDecimal anomalyThreshold;

    @TableField("status")
    private Integer status;

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

    public Long getWatchlistId() {
        return watchlistId;
    }

    public void setWatchlistId(Long watchlistId) {
        this.watchlistId = watchlistId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public BigDecimal getAnomalyThreshold() {
        return anomalyThreshold;
    }

    public void setAnomalyThreshold(BigDecimal anomalyThreshold) {
        this.anomalyThreshold = anomalyThreshold;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
