package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * subscription_config 表的持久化对象（PO）。
 *
 * <p>对齐 V11 迁移：id/user_id/sub_type/sub_key/channel/status/created_at/updated_at/version。 {@code
 * user_id} 为行级权限取数键， 仓储所有面向用户的查询 {@code WHERE user_id=?}。 {@code sub_type}/{@code channel}/{@code
 * status} 映射 {@code TINYINT}→{@code Integer}， 领域枚举在仓储实现层转换。时间戳存 ISO-8601 文本（SQLite 类型亲和）， 领域 {@link
 * com.info.platform.domain.subscription.Subscription} 的 {@code Instant} 在仓储实现层转换。
 */
@TableName("subscription_config")
public class SubscriptionPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("sub_type")
    private Integer subType;

    @TableField("sub_key")
    private String subKey;

    @TableField("channel")
    private Integer channel;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getSubType() {
        return subType;
    }

    public void setSubType(Integer subType) {
        this.subType = subType;
    }

    public String getSubKey() {
        return subKey;
    }

    public void setSubKey(String subKey) {
        this.subKey = subKey;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
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
