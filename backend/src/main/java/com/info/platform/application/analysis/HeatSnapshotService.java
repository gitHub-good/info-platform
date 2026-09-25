package com.info.platform.application.analysis;

import com.info.platform.domain.analysis.HeatCalculator;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 行业热度快照服务（应用层，M15 T123，方案 §4.5）：双窗（H24/D7）现算 31×2 行 → {@code industry_heat_snapshot} UPSERT（62
 * 行常驻，含 0 分沉底）+ basis 版本串 + prev 等长窗口环比。零 LLM（快照恒常跑，护栏不停——方案 §4.6）。
 *
 * <p>对账口径（§4.10）：快照行 score/news_count/event_count = HeatCalculator 对同窗口现算值（重算相等断言的现算侧）。
 */
@Service
public class HeatSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(HeatSnapshotService.class);

    private final HeatSnapshotRepository repository;
    private final PipelineSettings settings;
    private final Clock clock;

    public HeatSnapshotService(
            HeatSnapshotRepository repository, PipelineSettings settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 现算并 UPSERT 全部快照（定时与手动触发共用入口）。
     *
     * @return 快照报告（JobRunStats 留痕）
     */
    public SnapshotReport snapshotAll() {
        Instant now = clock.instant();
        HeatCalculator.HeatParams params = settings.heatParams();
        String basis = HeatCalculator.basis(params);
        List<IndustryHeatSnapshot> rows = new ArrayList<>();
        for (HeatWindow window : HeatWindow.values()) {
            rows.addAll(snapshotOfWindow(window, params, now, basis));
        }
        int upserted = repository.upsertAll(rows);
        SnapshotReport report =
                new SnapshotReport(
                        HeatWindow.values().length,
                        rows.size(),
                        "heat=h24:"
                                + countOf(rows, HeatWindow.H24)
                                + "; d7:"
                                + countOf(rows, HeatWindow.D7));
        log.info("行业热度快照完成: upsert={}/{}（basis={}）", upserted, rows.size(), basis);
        return report;
    }

    /** 单窗快照行产出（current + prev 两窗现算，31 行零填常驻）。 */
    private List<IndustryHeatSnapshot> snapshotOfWindow(
            HeatWindow window, HeatCalculator.HeatParams params, Instant windowEnd, String basis) {
        Duration length = window.length();
        Map<String, HeatCalculator.IndustryHeat> current =
                HeatCalculator.compute(
                        heatItems(windowEnd.minus(length), windowEnd), params, windowEnd, length);
        Instant prevEnd = windowEnd.minus(length);
        Map<String, HeatCalculator.IndustryHeat> prev =
                HeatCalculator.compute(
                        heatItems(prevEnd.minus(length), prevEnd), params, prevEnd, length);

        List<IndustryHeatSnapshot> rows = new ArrayList<>();
        for (String industry : IndustryCategory.SW_INDUSTRIES) {
            HeatCalculator.IndustryHeat now =
                    current.getOrDefault(industry, new HeatCalculator.IndustryHeat(0.0, 0, 0));
            HeatCalculator.IndustryHeat before =
                    prev.getOrDefault(industry, new HeatCalculator.IndustryHeat(0.0, 0, 0));
            rows.add(
                    IndustryHeatSnapshot.create(
                            industry,
                            window,
                            now.score(),
                            before.score(),
                            now.newsCount(),
                            now.eventCount(),
                            basis,
                            windowEnd));
        }
        return rows;
    }

    /** 窗口条目取数（仓储 WindowItem → 计算器 HeatItem）。 */
    private List<HeatCalculator.HeatItem> heatItems(Instant from, Instant to) {
        return repository.findWindowItems(from.toString(), to.toString()).stream()
                .map(
                        item ->
                                new HeatCalculator.HeatItem(
                                        item.mainCategory(),
                                        item.publishedAt(),
                                        item.eventImportance(),
                                        item.affectedIndustries()))
                .toList();
    }

    private static int countOf(List<IndustryHeatSnapshot> rows, HeatWindow window) {
        return (int) rows.stream().filter(row -> row.getWindow() == window).count();
    }

    /** 快照轮报告。 */
    public record SnapshotReport(int windows, int rows, String detail) {}
}
