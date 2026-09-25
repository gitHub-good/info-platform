package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import java.util.List;

/**
 * 行业热度榜视图（M15 T123，方案 §4.8 {@code GET /api/v1/industry-heat}）：31 行业降序 + 口径脚注数据面（basis/snapshotAt）+
 * 护栏徽章（降级横幅三处同源之一）。
 *
 * @param window 窗口线格式（H24 / D7）
 * @param industries 31 行降序（heatScore DESC、newsCount DESC、industry ASC——0 分沉底）
 * @param basis 口径版本串（参数热改后首快照起换串，榜单脚注直读）
 * @param snapshotAt 快照时刻（ISO 文本）
 * @param pipeline 护栏面（横幅数据面同源）
 */
public record IndustryHeatBoardView(
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
}
