package com.info.platform.domain.subscription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 自选清单聚合根（watchlist）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。 持久化字段（id/version/时间戳）由基础设施层 {@code WatchlistRepositoryImpl} 经
 * {@link #reconstruct} 回填；清单项作为子实体由仓储在读取时一并装载。
 *
 * <p>行级权限：{@code userId} 标识清单归属，仓储所有数据查询带 {@code ownerUserId} 过滤； 服务层取 {@code UserContext.get().userId()} 作
 * {@code ownerUserId}，用户只能操作自己的清单。
 */
public class Watchlist {

    private Long id;
    private Long userId;
    private String name;
    private String remark;
    private WatchlistStatus status;
    private List<WatchlistItem> items;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private Watchlist() {
        this.items = new ArrayList<>();
    }

    /** 构建新清单（id/version/时间戳留空，落库后回填）。 */
    public static Watchlist create(Long userId, String name, String remark) {
        Objects.requireNonNull(userId, "userId 必填");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        Watchlist w = new Watchlist();
        w.userId = userId;
        w.name = name;
        w.remark = remark;
        w.status = WatchlistStatus.ENABLED;
        return w;
    }

    /** 从持久化数据重建聚合根（基础设施层落库后回读时用；items 由仓储装载后传入）。 */
    public static Watchlist reconstruct(
            Long id,
            Long userId,
            String name,
            String remark,
            WatchlistStatus status,
            List<WatchlistItem> items,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        Watchlist w = new Watchlist();
        w.id = id;
        w.userId = userId;
        w.name = name;
        w.remark = remark;
        w.status = status;
        w.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        w.version = version;
        w.createdAt = createdAt;
        w.updatedAt = updatedAt;
        return w;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getRemark() {
        return remark;
    }

    public WatchlistStatus getStatus() {
        return status;
    }

    /** 清单项（不可变视图）。 */
    public List<WatchlistItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
