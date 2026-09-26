package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * 行业影响链实体（{@code industry_impact_chain} 表，M17 T144，V28）：单事件单行业一行（UNIQUE(event_id,
 * industry)——重生成由仓储整事件替换收敛）。行业须 ∈ 申万 31 白名单（实体把守，容器不进链——零新增事实红线）；{@code basis} 为依据回溯 JSON（信号来源条目 id
 * 集 / 事件结构化字段 / 原文引用）；生成方式 v1 恒模板态（{@link #GEN_METHOD_TEMPLATE}，纯规则映射无 LLM——走向判断的 AI 留在周报，任务 T144
 * 裁量）。
 */
public class IndustryImpactChain {

    /** 生成方式：v1 纯规则模板直出（LLM 失败/护栏降级态与常态同一路径——链路恒活）。 */
    public static final String GEN_METHOD_TEMPLATE = "TEMPLATE";

    private final Long id;
    private final long eventId;
    private final String industry;
    private final Direction direction;
    private final String logicChain;
    private final String basis;
    private final String templateKey;
    private final ImpactCacheState cacheState;
    private final Instant createdAt;
    private final Instant updatedAt;

    private IndustryImpactChain(
            Long id,
            long eventId,
            String industry,
            Direction direction,
            String logicChain,
            String basis,
            String templateKey,
            ImpactCacheState cacheState,
            Instant createdAt,
            Instant updatedAt) {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId 须为正: " + eventId);
        }
        if (!IndustryCategory.isSwIndustry(industry)) {
            throw new IllegalArgumentException("industry 须为申万 31 枚举（容器不进链）: " + industry);
        }
        if (logicChain == null || logicChain.isBlank()) {
            throw new IllegalArgumentException("logicChain 必填: " + logicChain);
        }
        this.id = id;
        this.eventId = eventId;
        this.industry = industry;
        this.direction = Objects.requireNonNull(direction, "direction 必填");
        this.logicChain = logicChain;
        this.basis = Objects.requireNonNull(basis, "basis 必填（依据回溯 JSON）");
        this.templateKey = Objects.requireNonNull(templateKey, "templateKey 必填");
        this.cacheState = Objects.requireNonNull(cacheState, "cacheState 必填");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建链行（模板渲染产物；id/时间戳由仓储回填）。 */
    public static IndustryImpactChain create(
            long eventId,
            String industry,
            Direction direction,
            String logicChain,
            String basis,
            String templateKey,
            ImpactCacheState cacheState,
            Instant now) {
        return new IndustryImpactChain(
                null,
                eventId,
                industry,
                direction,
                logicChain,
                basis,
                templateKey,
                cacheState,
                now,
                now);
    }

    /** 从持久化数据重建（基础设施层回读）。 */
    public static IndustryImpactChain reconstruct(
            Long id,
            long eventId,
            String industry,
            Direction direction,
            String logicChain,
            String basis,
            String templateKey,
            ImpactCacheState cacheState,
            Instant createdAt,
            Instant updatedAt) {
        return new IndustryImpactChain(
                id,
                eventId,
                industry,
                direction,
                logicChain,
                basis,
                templateKey,
                cacheState,
                createdAt,
                updatedAt);
    }

    public Long getId() {
        return id;
    }

    public long getEventId() {
        return eventId;
    }

    public String getIndustry() {
        return industry;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getLogicChain() {
        return logicChain;
    }

    /** 依据回溯 JSON 原文（newsId / signalNewsIds / 事件结构化字段 / quote）。 */
    public String getBasis() {
        return basis;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public ImpactCacheState getCacheState() {
        return cacheState;
    }

    /** 生成方式（v1 恒 {@link #GEN_METHOD_TEMPLATE}——纯规则映射）。 */
    public String getGenMethod() {
        return GEN_METHOD_TEMPLATE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
