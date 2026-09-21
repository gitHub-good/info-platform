package com.info.platform.domain.subscription;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 自选清单项值对象（watchlist_item）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型（含 {@link BigDecimal} 表达异动阈值），可脱离容器单测、可移植。 持久化字段（id/version/时间戳）由基础设施层 {@code
 * WatchlistRepositoryImpl} 经 {@link #reconstruct} 回填。
 *
 * <p>异动阈值默认 {@link #DEFAULT_THRESHOLD}（3.00%），与 DDL {@code DEFAULT 3.00} 对齐； DECIMAL(5,2) 映射 {@link
 * BigDecimal} 避免浮点精度损失。
 */
public class WatchlistItem {

    /** 异动阈值默认值（%），与 watchlist_item.anomaly_threshold DEFAULT 3.00 对齐。 */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("3.00");

    private Long id;
    private Long watchlistId;
    private Long subjectId;
    private BigDecimal anomalyThreshold;
    private WatchlistStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private WatchlistItem() {}

    /** 构建新清单项（id/version/时间戳留空，落库后回填）。 */
    public static WatchlistItem create(
            Long watchlistId, Long subjectId, BigDecimal anomalyThreshold) {
        Objects.requireNonNull(watchlistId, "watchlistId 必填");
        Objects.requireNonNull(subjectId, "subjectId 必填");
        WatchlistItem item = new WatchlistItem();
        item.watchlistId = watchlistId;
        item.subjectId = subjectId;
        item.anomalyThreshold = anomalyThreshold != null ? anomalyThreshold : DEFAULT_THRESHOLD;
        item.status = WatchlistStatus.ENABLED;
        return item;
    }

    /** 从持久化数据重建值对象（基础设施层落库后回读时用）。 */
    public static WatchlistItem reconstruct(
            Long id,
            Long watchlistId,
            Long subjectId,
            BigDecimal anomalyThreshold,
            WatchlistStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        WatchlistItem item = new WatchlistItem();
        item.id = id;
        item.watchlistId = watchlistId;
        item.subjectId = subjectId;
        item.anomalyThreshold = anomalyThreshold;
        item.status = status;
        item.version = version;
        item.createdAt = createdAt;
        item.updatedAt = updatedAt;
        return item;
    }

    /** 改异动阈值（领域行为：非空且非负）。 */
    public void updateThreshold(BigDecimal newThreshold) {
        Objects.requireNonNull(newThreshold, "anomalyThreshold 不能为空");
        if (newThreshold.signum() < 0) {
            throw new IllegalArgumentException("anomalyThreshold 不能为负");
        }
        this.anomalyThreshold = newThreshold;
    }

    public Long getId() {
        return id;
    }

    public Long getWatchlistId() {
        return watchlistId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public BigDecimal getAnomalyThreshold() {
        return anomalyThreshold;
    }

    public WatchlistStatus getStatus() {
        return status;
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
