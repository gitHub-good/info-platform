package com.info.platform.application.analysis;

import com.info.platform.domain.aggregation.Market;
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
 * 行业热度快照服务（应用层，M15 T123，方案 §4.5；M29 T255 三市场化）：双窗（H24/D7）× 三市场（A_SHARE/HK/US）现算各市场枚举×2 行 → {@code
 * industry_heat_snapshot} UPSERT（常驻，含 0 分沉底）+ basis 版本串 + prev 等长窗口环比。零 LLM（快照恒常跑，护栏不停——方案 §4.6）。
 *
 * <p>分桶口径（方案 §4 C11）：窗口条目按 {@code l1_market} 过滤（V37 ⑧ 消歧键——港美股资讯经 T253 源流入 + L1 v2.0 归类后自然累积，
 * 不混桶）；A 股路径输入集与 v1 零变化（枚举/窗口逻辑零变化）。
 *
 * <p>对账口径（§4.10）：快照行 score/news_count/event_count = HeatCalculator 对同窗口现算值（重算相等断言的现算侧）。
 */
@Service
public class HeatSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(HeatSnapshotService.class);

    /** 快照市场序（确定性——A 股首拉通后逐市场追加，报告 detail 按此序拼装）。 */
    private static final List<Market> MARKETS = List.of(Market.A_SHARE, Market.HK, Market.US);

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
     * 现算并 UPSERT 全部快照（定时与手动触发共用入口；三市场 × 双窗全量常驻）。
     *
     * @return 快照报告（JobRunStats 留痕）
     */
    public SnapshotReport snapshotAll() {
        Instant now = clock.instant();
        HeatCalculator.HeatParams params = settings.heatParams();
        String basis = HeatCalculator.basis(params);
        List<IndustryHeatSnapshot> rows = new ArrayList<>();
        for (Market market : MARKETS) {
            for (HeatWindow window : HeatWindow.values()) {
                rows.addAll(snapshotOfWindow(market, window, params, now, basis));
            }
        }
        int upserted = repository.upsertAll(rows);
        SnapshotReport report =
                new SnapshotReport(
                        HeatWindow.values().length,
                        rows.size(),
                        "a:"
                                + countOf(rows, Market.A_SHARE)
                                + "; hk:"
                                + countOf(rows, Market.HK)
                                + "; us:"
                                + countOf(rows, Market.US));
        log.info("行业热度快照完成: upsert={}/{}（basis={}）", upserted, rows.size(), basis);
        return report;
    }

    /** 单市场单窗快照行产出（current + prev 两窗现算，各市场枚举全量零填常驻）。 */
    private List<IndustryHeatSnapshot> snapshotOfWindow(
            Market market,
            HeatWindow window,
            HeatCalculator.HeatParams params,
            Instant windowEnd,
            String basis) {
        Duration length = window.length();
        Map<String, HeatCalculator.IndustryHeat> current =
                HeatCalculator.compute(
                        heatItems(market, windowEnd.minus(length), windowEnd),
                        params,
                        windowEnd,
                        length,
                        market);
        Instant prevEnd = windowEnd.minus(length);
        Map<String, HeatCalculator.IndustryHeat> prev =
                HeatCalculator.compute(
                        heatItems(market, prevEnd.minus(length), prevEnd),
                        params,
                        prevEnd,
                        length,
                        market);

        List<IndustryHeatSnapshot> rows = new ArrayList<>();
        for (String industry : boardIndustriesOf(market)) {
            HeatCalculator.IndustryHeat now =
                    current.getOrDefault(industry, new HeatCalculator.IndustryHeat(0.0, 0, 0));
            HeatCalculator.IndustryHeat before =
                    prev.getOrDefault(industry, new HeatCalculator.IndustryHeat(0.0, 0, 0));
            rows.add(
                    IndustryHeatSnapshot.create(
                            market,
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

    /** 市场进榜枚举零填序（Collator 中文序确定性——Set 无序转稳定注入序）。 */
    private static List<String> boardIndustriesOf(Market market) {
        if (market == Market.HK) {
            return ClassificationService.COLLATOR_ZH.sorted(IndustryCategory.HK_INDUSTRIES);
        }
        if (market == Market.US) {
            return ClassificationService.COLLATOR_ZH.sorted(IndustryCategory.US_INDUSTRIES);
        }
        return ClassificationService.SW_ENUM_ORDER;
    }

    /** 窗口条目取数（按市场过滤——仓储 WindowItem → 计算器 HeatItem）。 */
    private List<HeatCalculator.HeatItem> heatItems(Market market, Instant from, Instant to) {
        return repository.findWindowItems(from.toString(), to.toString(), market).stream()
                .map(
                        item ->
                                new HeatCalculator.HeatItem(
                                        item.mainCategory(),
                                        item.publishedAt(),
                                        item.eventImportance(),
                                        item.affectedIndustries()))
                .toList();
    }

    private static int countOf(List<IndustryHeatSnapshot> rows, Market market) {
        return (int) rows.stream().filter(row -> row.getMarket() == market).count();
    }

    /** 快照轮报告。 */
    public record SnapshotReport(int windows, int rows, String detail) {}
}
