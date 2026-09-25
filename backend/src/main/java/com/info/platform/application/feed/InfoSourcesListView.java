package com.info.platform.application.feed;

import java.util.List;

/**
 * 源列表分组视图（M13 T105，方案 §4.5 GET /info-sources）：启用中源按 category 分组（分组顺序见 服务类 CATEGORY_ORDER） +
 * 软删源单独成组（编排者裁定：恢复交互依赖归档区可见）。
 *
 * @param groups 启用/停用（未软删）源分组
 * @param archived 软删源（恢复回停用态）
 */
public record InfoSourcesListView(List<CategoryGroup> groups, List<InfoSourceCardView> archived) {

    /** category 分组（组内按 id 升序）。 */
    public record CategoryGroup(String category, List<InfoSourceCardView> sources) {}
}
