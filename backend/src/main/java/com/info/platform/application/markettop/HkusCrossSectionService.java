package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.valuation.FactorSnapshotService;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.valuation.CatalystFactor;
import com.info.platform.domain.valuation.ConductionFactor;
import com.info.platform.domain.valuation.FactorEntry;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.EventRef;
import com.info.platform.domain.valuation.FactorSnapshotRepository.IndustryMemberRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.NewsLinkRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.IndustryAssociator;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import com.info.platform.domain.valuation.IndustryAssociator.MemberLink;
import com.info.platform.domain.valuation.RiskFactor;
import com.info.platform.domain.valuation.ScoreComposer;
import com.info.platform.domain.valuation.ValuationEvent;
import com.info.platform.domain.valuation.ValuationParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 港美股漏斗横截面就地现算（应用层，M29 T256，方案 §7.1/§7.2 + 拍板四）：因子快照管道 A 股-only（F3 基本面 / F5 估值依赖面 Won't
 * W1/W3），港美股榜单在第 1 层按市场横截面现算可得维——
 *
 * <ul>
 *   <li>F1 事件催化 / F4 风险安全 / F2 行业传导：复用 M20 领域纯函数（输入契约市场无关）；F2 名次归一按市场枚举行数（港 31 / 美
 *       40），行业白名单按市场切换（{@code IndustryCategory.isBoardIndustry}）；
 *   <li>F3/F5 缺省：<b>权重置 0 后剩余维再归一</b>（非简单低分，ScoreComposer 归一合成既有语义），{@code
 *       dimensionMissing}/data_flags 留痕在 MarketTopService 落库面；
 *   <li>输出 = 与 A 股快照行同构的 {@link PoolRow}（下游粗筛四键/深析跳过/Top 合成/落库完全共用——四层漏斗同构不同数据）。
 * </ul>
 *
 * <p>输入按市场裁剪（事件/资讯回联行按标的代码交集过滤，跨市场重名行业由 market 消歧）；零 LLM 零外呼；同输入同输出（纯函数链）。
 */
@Service
public class HkusCrossSectionService {

    private static final Logger log = LoggerFactory.getLogger(HkusCrossSectionService.class);

    private final FactorSnapshotRepository snapshotRepository;

    private final ValuationSettings settings;

    private final ObjectMapper objectMapper;

    public HkusCrossSectionService(
            FactorSnapshotRepository snapshotRepository,
            ValuationSettings settings,
            ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    /** 横截面产物（rows = 快照行同构池行；params = 本轮有效参数——F3/F5 置 0 后口径，basis 审计锚与打分同源）。 */
    public record HkusCrossSection(List<PoolRow> rows, ValuationParams params) {}

    /**
     * 现算该市场横截面（榜单第 1 层——池 = market ∩ status=1）。
     *
     * @param rankDate 榜单口径日（窗口与衰减锚点，Asia/Shanghai）
     * @param market HK / US（A 股走因子快照管道不进本类）
     */
    public HkusCrossSection rowsFor(LocalDate rankDate, Market market) {
        List<SubjectRef> subjects = snapshotRepository.findActiveSubjects(market);
        if (subjects.isEmpty()) {
            return new HkusCrossSection(List.of(), hkusParams(settings.params()));
        }
        ValuationParams base = settings.params(); // 热读（与 A 股快照轮同一权重版本口径）
        ValuationParams hkus = hkusParams(base);
        String dateText = rankDate.toString();

        Set<String> marketCodes = new HashSet<>();
        for (SubjectRef subject : subjects) {
            marketCodes.add(subject.code());
        }
        List<EventRef> events =
                filterEvents(
                        snapshotRepository.findEventsInWindow(
                                rankDate.minusDays(base.assocWindowDays() - 1L).toString(),
                                dateText),
                        marketCodes);
        List<NewsLinkRow> news =
                filterNews(
                        snapshotRepository.findMatchedNewsInWindow(
                                FactorSnapshotService.windowStartIso(
                                        rankDate, base.assocWindowDays()),
                                FactorSnapshotService.dayEndIso(rankDate)),
                        marketCodes);
        List<MemberLink> members = memberLinks(snapshotRepository.findIndustryMembers(market));
        List<HeatRow> heatRows = snapshotRepository.findH24Heat(market);
        Map<String, List<ValuationEvent>> eventsByCode = eventsByCode(events);
        Map<String, List<Association>> associations =
                IndustryAssociator.associate(
                        FactorSnapshotService.eventLinks(events),
                        FactorSnapshotService.newsLinks(news),
                        members,
                        rankDate,
                        base.assocWindowDays(),
                        market);
        int industryRows =
                market == Market.HK ? IndustryCategory.hkSize() : IndustryCategory.usSize();

        List<PoolRow> rows = new ArrayList<>(subjects.size());
        for (SubjectRef subject : subjects) {
            rows.add(
                    rowOf(
                            subject,
                            rankDate,
                            base,
                            hkus,
                            eventsByCode.getOrDefault(subject.code(), List.of()),
                            associations.getOrDefault(subject.code(), List.of()),
                            heatRows,
                            industryRows));
        }
        log.info(
                "港美股横截面现算完成: market={} subjects={} events={} news={} heatRows={}",
                market,
                rows.size(),
                events.size(),
                news.size(),
                heatRows.size());
        return new HkusCrossSection(List.copyOf(rows), hkus);
    }

    /** F3/F5 权重置 0（剩余维再归一的参数形态——ScoreComposer 归一除法天然承载）。 */
    private static ValuationParams hkusParams(ValuationParams base) {
        return base.withWFundamental(0.0).withWValuation(0.0);
    }

    /** 单标的行组装（五维中三维现算 + 两维缺省 0——与 A 股 rowOf 同构的落库粒度舍入）。 */
    private PoolRow rowOf(
            SubjectRef subject,
            LocalDate rankDate,
            ValuationParams base,
            ValuationParams hkus,
            List<ValuationEvent> subjectEvents,
            List<Association> subjectAssociations,
            List<HeatRow> heatRows,
            int industryRows) {
        CatalystFactor.Result f1 = CatalystFactor.compute(subjectEvents, rankDate, base);
        ConductionFactor.Result f2 =
                ConductionFactor.compute(subjectAssociations, heatRows, base, industryRows);
        RiskFactor.Result f4 = RiskFactor.compute(subjectEvents, subject.name(), rankDate, base);
        double f1r = round1(f1.score());
        double f2r = round1(f2.score());
        double f4r = round1(f4.score());
        ScoreComposer.Composed composed = ScoreComposer.compose(f1r, f2r, 0.0, f4r, 0.0, hkus);
        return new PoolRow(
                subject.id(),
                subject.code(),
                subject.name(),
                null, // 行业维经 F2 关联集消费（成员边已是市场枚举），池行不冗余携带
                f1r,
                f2r,
                0.0,
                f4r,
                0.0,
                composed.totalScore(),
                composed.breakthrough(),
                detailJson(f1, f2, f4),
                hkus.basis(),
                lastEventDateText(f1, f4));
    }

    /** 最近事件日（max(catalyst/risk 依据事件)——与 A 股口径一致去掉 fundamental 维（缺省））。 */
    private static String lastEventDateText(CatalystFactor.Result f1, RiskFactor.Result f4) {
        LocalDate latest = null;
        for (List<FactorEntry> entries : List.of(f1.entries(), f4.entries())) {
            for (FactorEntry entry : entries) {
                if (latest == null || entry.eventDate().isAfter(latest)) {
                    latest = entry.eventDate();
                }
            }
        }
        return latest == null ? null : latest.toString();
    }

    /**
     * factor_detail 同契约子集（catalyst/conduction/risk——fundamental/valuation 缺省不造假；evidence_count
     * 派生同源）。
     */
    private String detailJson(
            CatalystFactor.Result f1, ConductionFactor.Result f2, RiskFactor.Result f4) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(
                "catalyst",
                Map.of("raw", f1.raw(), "entries", FactorSnapshotService.entryMaps(f1.entries())));
        detail.put("conduction", Map.of("assoc", f2.assoc()));
        detail.put(
                "risk",
                Map.of(
                        "stFlag", f4.stFlag(),
                        "eventPenalty", f4.eventPenalty(),
                        "entries", FactorSnapshotService.entryMaps(f4.entries())));
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("港美股因子明细 JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 事件按市场代码裁剪（跨市场事件各市场各记各的标的边）。 */
    private static List<EventRef> filterEvents(List<EventRef> events, Set<String> marketCodes) {
        List<EventRef> filtered = new ArrayList<>(events.size());
        for (EventRef ref : events) {
            if (ref.subjectCodes().stream().anyMatch(marketCodes::contains)) {
                filtered.add(ref);
            }
        }
        return filtered;
    }

    /** 资讯回联行按市场代码裁剪。 */
    private static List<NewsLinkRow> filterNews(List<NewsLinkRow> news, Set<String> marketCodes) {
        List<NewsLinkRow> filtered = new ArrayList<>(news.size());
        for (NewsLinkRow row : news) {
            if (row.subjectCodes().stream().anyMatch(marketCodes::contains)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /** 事件投影 → code → 事件集（市场代码定界——A 股代码键天然隔离）。 */
    private static Map<String, List<ValuationEvent>> eventsByCode(List<EventRef> events) {
        Map<String, List<ValuationEvent>> byCode = new HashMap<>();
        for (EventRef ref : events) {
            for (String code : ref.subjectCodes()) {
                byCode.computeIfAbsent(code, unused -> new ArrayList<>()).add(ref.event());
            }
        }
        return byCode;
    }

    /** 成员边（港美股 F10 枚举免 SW 映射直连；UNKNOWN/未收录由关联器白名单过滤）。 */
    private static List<MemberLink> memberLinks(List<IndustryMemberRow> rows) {
        List<MemberLink> links = new ArrayList<>(rows.size());
        for (IndustryMemberRow row : rows) {
            links.add(new MemberLink(row.code(), row.industry()));
        }
        return links;
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
