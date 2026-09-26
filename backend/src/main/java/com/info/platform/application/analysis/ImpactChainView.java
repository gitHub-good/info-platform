package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 事件影响链视图（M17 T144，{@code GET /api/v1/events/{id}/impact-chains} 契约）：eligibility 四态——AUTO（HIGH
 * 落库自动/查询侧自愈）/ ON_DEMAND（MEDIUM 本次按需生成并缓存）/ CACHED（已有缓存）/ LOW_SKIPPED（LOW 不生成空态）； 每链附 basis 依据回溯
 * JSON（newsId / signalNewsIds / 事件结构化字段 / quote）可展开回溯。
 */
public record ImpactChainView(
        long eventId,
        String importance,
        String eligibility,
        List<ChainItemView> chains,
        String disclaimer) {

    /** 单链行（行业 / 方向 / 逻辑链 / 依据回溯 / 模板键 / 缓存态 / 生成方式）。 */
    public record ChainItemView(
            long id,
            String industry,
            String direction,
            String logicChain,
            JsonNode basis,
            String templateKey,
            String cacheState,
            String genMethod) {}
}
