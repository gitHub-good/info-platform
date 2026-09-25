package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * 行业热度快照实体（{@code industry_heat_snapshot} 表，M15 T123，方案 §4.5）：UNIQUE(industry, window_type) 62 行常驻
 * UPSERT 当前值（历史趋势由日报 heat_top 留存）；容器 4 枚举不进榜（实体把守申万白名单）。
 */
public class IndustryHeatSnapshot {

    private final Long id;
    private final String industry;
    private final HeatWindow window;
    private final double heatScore;
    private final double prevScore;
    private final double deltaPct;
    private final long newsCount;
    private final long eventCount;
    private final String basis;
    private final Instant snapshotAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private IndustryHeatSnapshot(
            Long id,
            String industry,
            HeatWindow window,
            double heatScore,
            double prevScore,
            double deltaPct,
            long newsCount,
            long eventCount,
            String basis,
            Instant snapshotAt,
            Instant createdAt,
            Instant updatedAt) {
        if (!IndustryCategory.isSwIndustry(industry)) {
            throw new IllegalArgumentException("industry 须为申万 31 枚举（容器不进榜）: " + industry);
        }
        this.id = id;
        this.industry = industry;
        this.window = Objects.requireNonNull(window, "window 必填");
        this.heatScore = requireNonNegative(heatScore, "heatScore");
        this.prevScore = requireNonNegative(prevScore, "prevScore");
        this.deltaPct = deltaPct;
        this.newsCount = requireNonNegative(newsCount, "newsCount");
        this.eventCount = requireNonNegative(eventCount, "eventCount");
        this.basis = Objects.requireNonNull(basis, "basis 必填（口径版本串）");
        this.snapshotAt = Objects.requireNonNull(snapshotAt, "snapshotAt 必填");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private static double requireNonNegative(double value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " 须非负: " + value);
        }
        return value;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " 须非负: " + value);
        }
        return value;
    }

    /** 新建快照行（31×2 批量产出之一；id/时间戳由仓储回填）。 */
    public static IndustryHeatSnapshot create(
            String industry,
            HeatWindow window,
            double heatScore,
            double prevScore,
            long newsCount,
            long eventCount,
            String basis,
            Instant snapshotAt) {
        return new IndustryHeatSnapshot(
                null,
                industry,
                window,
                heatScore,
                prevScore,
                deltaPctOf(heatScore, prevScore),
                newsCount,
                eventCount,
                basis,
                snapshotAt,
                null,
                null);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static IndustryHeatSnapshot reconstruct(
            Long id,
            String industry,
            HeatWindow window,
            double heatScore,
            double prevScore,
            double deltaPct,
            long newsCount,
            long eventCount,
            String basis,
            Instant snapshotAt,
            Instant createdAt,
            Instant updatedAt) {
        return new IndustryHeatSnapshot(
                id,
                industry,
                window,
                heatScore,
                prevScore,
                deltaPct,
                newsCount,
                eventCount,
                basis,
                snapshotAt,
                createdAt,
                updatedAt);
    }

    /** 环比：prev=0 且 score&gt;0 记 100.0，双 0 记 0（表注释口径）。 */
    static double deltaPctOf(double score, double prev) {
        if (prev <= 0) {
            return score > 0 ? 100.0 : 0.0;
        }
        return (score - prev) / prev * 100.0;
    }

    public Long getId() {
        return id;
    }

    public String getIndustry() {
        return industry;
    }

    public HeatWindow getWindow() {
        return window;
    }

    public double getHeatScore() {
        return heatScore;
    }

    public double getPrevScore() {
        return prevScore;
    }

    public double getDeltaPct() {
        return deltaPct;
    }

    public long getNewsCount() {
        return newsCount;
    }

    public long getEventCount() {
        return eventCount;
    }

    public String getBasis() {
        return basis;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
