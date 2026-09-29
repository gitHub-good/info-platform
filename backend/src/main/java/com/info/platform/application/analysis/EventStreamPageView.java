package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.IndustryCategory;
import java.util.List;

/**
 * 事件流页码模式视图（M25 T220，V3.0 前端改版 REQ-20260928-21 拍板四）：page 参数出现即页码模式（M9 PageQuery 语义，offset 分页），
 * 与游标模式 {@link EventStreamView} 契约各自闭合（无 nextBeforeId 字段）；卡片字段面复用 {@code EventCardView}。
 *
 * <p>M29 T254 补交付（P1-01 修复，方案 §5.4）：新增 {@code industryFilterGroups}——三市场行业枚举分组（A_SHARE 31 申万 / HK 31
 * 直采 / US 40 归并，容器 4 与 UNKNOWN 不入选），三市场口径不混排（拍板二）；前端过滤器下拉按 market 分组消费（缺省回 SW 31 容错）。
 *
 * @param total 当前筛选总数（与游标模式同源 countStreamItems——两模式计数一致）
 * @param items 当前页事件卡（id DESC，同游标模式排序）
 * @param page 页码（1 起，如实回显）
 * @param size 页大小（缺省 20，上限 50 由 PageQuery 校验）
 * @param industryFilterGroups 行业过滤器分组（恒三组，枚举集为冻结契约——改枚举属契约变更回架构）
 */
public record EventStreamPageView(
        long total,
        List<EventStreamView.EventCardView> items,
        int page,
        int size,
        List<IndustryFilterGroupView> industryFilterGroups) {

    /** 行业过滤器分组元素（{market, industries}——各市场进榜行业枚举集）。 */
    public record IndustryFilterGroupView(String market, List<String> industries) {}

    /**
     * 三市场行业过滤器分组（确定性输出序：A_SHARE = 申万 31 冻结序（与前端 SW_INDUSTRIES 兜底同序）；HK/US = Collator 中文序）。
     */
    public static final List<IndustryFilterGroupView> INDUSTRY_FILTER_GROUPS =
            List.of(
                    new IndustryFilterGroupView(
                            Market.A_SHARE.name(), ClassificationService.SW_ENUM_ORDER),
                    new IndustryFilterGroupView(
                            Market.HK.name(),
                            ClassificationService.COLLATOR_ZH.sorted(
                                    IndustryCategory.HK_INDUSTRIES)),
                    new IndustryFilterGroupView(
                            Market.US.name(),
                            ClassificationService.COLLATOR_ZH.sorted(
                                    IndustryCategory.US_INDUSTRIES)));
}
