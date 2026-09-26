package com.info.platform.domain.analysis;

import java.util.List;

/**
 * 行业影响链仓储端口（{@code industry_impact_chain}，M17 T144，V28）。领域层纯净接口：写路径整事件替换（重生成幂等—— UNIQUE(event_id,
 * industry) 收敛为本次产物）；读路径供事件详情影响链区块（{@code GET /api/v1/events/{id}/impact-chains}）。
 */
public interface ImpactChainRepository {

    /**
     * 整事件替换落链（DELETE + INSERT 事务语义，实现层保证原子）。
     *
     * @return 实插行数
     */
    int replaceForEvent(long eventId, List<IndustryImpactChain> chains);

    /** 按事件读链（event_id 升序无关——按行业序呈现由渲染层决定；无行返回空清单）。 */
    List<IndustryImpactChain> findByEventId(long eventId);
}
