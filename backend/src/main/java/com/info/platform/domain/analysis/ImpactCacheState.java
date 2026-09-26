package com.info.platform.domain.analysis;

/**
 * 影响链缓存态（{@code industry_impact_chain.cache_state}，M17 T144，REQ 拍板五-5）：HIGH 落库自动（AUTO）/ MEDIUM
 * 首次展开按需生成并缓存（ON_DEMAND）/ LOW 不生成（无行——空态语义，无第三枚举）。
 */
public enum ImpactCacheState {
    /** HIGH 事件 L2 落库后自动生成（挂 {@code EventExtractionService.persist}）。 */
    AUTO("落库自动"),
    /** MEDIUM 事件首次展开影响链区块时按需生成并缓存留痕（再次展开不重复生成）。 */
    ON_DEMAND("按需缓存");

    private final String displayName;

    ImpactCacheState(String displayName) {
        this.displayName = displayName;
    }

    /** 中文展示名（前端模板态/缓存态标注用）。 */
    public String displayName() {
        return displayName;
    }
}
