package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import java.util.List;

/**
 * 行业热度榜视图（M15 T123，方案 §4.8 {@code GET /api/v1/industry-heat}；M29 T255 增 market 参数）：各市场枚举行的降序榜 +
 * 口径脚注数据面（basis/snapshotAt）+ 护栏徽章（降级横幅三处同源之一）。
 *
 * @param market 市场口径（A_SHARE / HK / US——M29 T255 回显，缺省 A_SHARE）
 * @param industrySystem 行业体系口径标注（拍板二——分市场枚举不混排）
 * @param window 窗口线格式（H24 / D7）
 * @param industries 进榜行业降序（heatScore DESC、newsCount DESC、industry ASC——0 分沉底）
 * @param basis 口径版本串（参数热改后首快照起换串，榜单脚注直读）
 * @param snapshotAt 快照时刻（ISO 文本）
 * @param pipeline 护栏面（横幅数据面同源）
 */
public record IndustryHeatBoardView(
        String market,
        String industrySystem,
        String window,
        List<RowView> industries,
        String basis,
        String snapshotAt,
        PipelineBadgeView pipeline) {

    /** 榜单行。 */
    public record RowView(
            String industry,
            double heatScore,
            double prevScore,
            double deltaPct,
            long newsCount,
            long eventCount) {

        static RowView of(IndustryHeatSnapshot snapshot) {
            return new RowView(
                    snapshot.getIndustry(),
                    snapshot.getHeatScore(),
                    snapshot.getPrevScore(),
                    snapshot.getDeltaPct(),
                    snapshot.getNewsCount(),
                    snapshot.getEventCount());
        }
    }

    /** 护栏徽章（降级横幅数据面；level 线格式 NORMAL/DEGRADED/FUSED）。 */
    public record PipelineBadgeView(String level) {}

    /** 视图组装（market 回显 + 口径标注；window 恒取解析值——空榜时窗口线仍在）。 */
    public static IndustryHeatBoardView of(
            Market market,
            HeatWindow window,
            List<IndustryHeatSnapshot> rows,
            PipelineBadgeView badge) {
        return new IndustryHeatBoardView(
                market.name(),
                IndustryCategory.industrySystemOf(market),
                window.name(),
                rows.stream().map(RowView::of).toList(),
                rows.isEmpty() ? null : rows.get(0).getBasis(),
                rows.isEmpty() ? null : rows.get(0).getSnapshotAt().toString(),
                badge);
    }
}
