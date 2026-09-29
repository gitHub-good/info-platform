package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.markettop.HkusCrossSectionService.HkusCrossSection;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.EventRef;
import com.info.platform.domain.valuation.FactorSnapshotRepository.IndustryMemberRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.NewsLinkRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.FactorSnapshotRepository.SubjectRef;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.ValuationEvent;
import com.info.platform.domain.valuation.ValuationParams;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HkusCrossSectionService 单测（M29 T256，方案 §7.1/§7.2 + 拍板四）：五维缺省再归一（F3/F5 权重置 0，非简单低分）/ 输入按市场
 * 裁剪（事件/资讯回联 代码交集——跨市场事件各记各的）/ 分市场行业白名单（A 股 SW-only 名在港美股不出边）/ F2 名次归一按市场枚举行数（US 40）/ 空池零行。
 */
@ExtendWith(MockitoExtension.class)
class HkusCrossSectionServiceTest {

    private static final LocalDate RANK_DATE = LocalDate.of(2026, 9, 29);

    private static final ValuationParams PARAMS =
            ValuationParams.defaults(); // 0.40|0.20|0.20|0.20|0.00

    @Mock private FactorSnapshotRepository snapshotRepository;

    @Mock private ValuationSettings settings;

    private HkusCrossSectionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new HkusCrossSectionService(snapshotRepository, settings, objectMapper);
        lenient().when(settings.params()).thenReturn(PARAMS);
    }

    private static SubjectRef subject(long id, String code) {
        return new SubjectRef(id, code, "标的" + id);
    }

    private static EventRef eventOf(
            long eventId, String eventDate, List<String> codes, List<String> industries) {
        return new EventRef(
                new ValuationEvent(
                        eventId,
                        "事件" + eventId,
                        LocalDate.parse(eventDate),
                        Direction.BULLISH,
                        Importance.HIGH,
                        EventType.MAJOR_CONTRACT),
                codes,
                industries);
    }

    // ---- 缺省再归一与纯函数链 ----

    @Test
    void rowsFor_hk_renormalizesOverAvailableDimsWithZeroedValueWeights() {
        // HK00700：F1 = 事件催化（HIGH×BULLISH×合同 coef 1.0，age0 衰减 1）→ 100×1/(1+K1)=25；
        // F2 = 路 A 事件回联（银行 affected 权重 1.0 × heatNorm 第 1 名 = 1.0）→ 100；F4 = 100（无利空事件）
        when(snapshotRepository.findActiveSubjects(Market.HK))
                .thenReturn(List.of(subject(701, "HK00700")));
        when(snapshotRepository.findEventsInWindow("2026-08-31", "2026-09-29"))
                .thenReturn(
                        List.of(
                                eventOf(
                                        101,
                                        "2026-09-29",
                                        List.of("HK00700", "SH600519"), // 跨市场事件：各市场各记各的标的边
                                        List.of("银行"))));
        when(snapshotRepository.findMatchedNewsInWindow(
                        "2026-08-30T16:00:00Z", "2026-09-29T16:00:00Z"))
                .thenReturn(List.of());
        when(snapshotRepository.findIndustryMembers(Market.HK))
                .thenReturn(List.of(new IndustryMemberRow("HK00700", "银行")));
        when(snapshotRepository.findH24Heat(Market.HK))
                .thenReturn(List.of(new HeatRow("银行", 90.0), new HeatRow("保险", 10.0)));

        HkusCrossSection section = service.rowsFor(RANK_DATE, Market.HK);

        assertThat(section.rows()).hasSize(1);
        PoolRow row = section.rows().get(0);
        assertThat(row.fCatalyst()).isEqualTo(25.0);
        assertThat(row.fConduction()).isEqualTo(100.0);
        assertThat(row.fFundamental()).isZero();
        assertThat(row.fValuation()).isZero();
        assertThat(row.fRisk()).isEqualTo(100.0);
        // F3/F5 权重置 0 后剩余维再归一（拍板四——非简单低分）：(0.4×25 + 0.2×100 + 0.2×100)/0.8
        assertThat(row.totalScore()).isEqualTo(62.5);
        assertThat(row.breakthrough()).isTrue(); // F1≥20 ∧ F2≥50 ∧ F4≥80
        assertThat(row.lastEventDate()).isEqualTo("2026-09-29");
        assertThat(row.weightBasis())
                .isEqualTo(PARAMS.withWFundamental(0).withWValuation(0).basis()); // 审计锚
        assertThat(row.factorDetailJson()).contains("\"eventId\":101").contains("\"银行\"");
        assertThat(row.subjectCode()).isEqualTo("HK00700"); // 快照行同构——下游漏斗零分支
    }

    @Test
    void rowsFor_us_normalizesHeatRankOverFortyIndustryRows() {
        when(snapshotRepository.findActiveSubjects(Market.US))
                .thenReturn(List.of(subject(9, "USAAPL")));
        when(snapshotRepository.findEventsInWindow("2026-08-31", "2026-09-29"))
                .thenReturn(List.of());
        when(snapshotRepository.findMatchedNewsInWindow(
                        "2026-08-30T16:00:00Z", "2026-09-29T16:00:00Z"))
                .thenReturn(
                        List.of(new NewsLinkRow(List.of("USAAPL"), "软件与信息服务", null, RANK_DATE)));
        when(snapshotRepository.findIndustryMembers(Market.US))
                .thenReturn(List.of(new IndustryMemberRow("USAAPL", "软件与信息服务")));
        when(snapshotRepository.findH24Heat(Market.US))
                .thenReturn(
                        List.of(new HeatRow("制药", 90.0), new HeatRow("软件与信息服务", 80.0))); // rank 2

        HkusCrossSection section = service.rowsFor(RANK_DATE, Market.US);

        PoolRow row = section.rows().get(0);
        // US 40 行枚举归一（区别于 A 股/HK 31 行）：rank 2 → (40−2)/39；news 主关联权重 1.0（高于成员边 0.3）
        assertThat(row.fConduction()).isEqualTo(round1(100.0 * (40 - 2) / 39.0));
        // 无事件无风险 → F1=0/F4=100：noSignal 判据 F1=0 ∧ F2≠0 → 可入池
        assertThat(row.fCatalyst()).isZero();
    }

    // ---- 输入市场裁剪与白名单 ----

    @Test
    void rowsFor_marketScopesInputsAndWhitelists() {
        when(snapshotRepository.findActiveSubjects(Market.HK))
                .thenReturn(List.of(subject(701, "HK00700"), subject(702, "HK09988")));
        when(snapshotRepository.findEventsInWindow("2026-08-31", "2026-09-29"))
                .thenReturn(
                        List.of(eventOf(101, "2026-09-28", List.of("SH600519"), List.of("银行"))));
        when(snapshotRepository.findMatchedNewsInWindow(
                        "2026-08-30T16:00:00Z", "2026-09-29T16:00:00Z"))
                .thenReturn(
                        List.of(
                                new NewsLinkRow(
                                        List.of("SH600519"),
                                        "美容护理",
                                        null,
                                        RANK_DATE), // A 股-only 代码 → 整行裁掉
                                new NewsLinkRow(
                                        List.of("HK09988"),
                                        "美容护理", // SW-only 名（非 US/HK 枚举）→ 白名单不出边
                                        null,
                                        RANK_DATE)));
        when(snapshotRepository.findIndustryMembers(Market.HK))
                .thenReturn(
                        List.of(
                                new IndustryMemberRow("HK00700", "UNKNOWN"), // 兜底枚举不进榜 → 无成员边
                                new IndustryMemberRow("HK09988", "软件服务")));
        when(snapshotRepository.findH24Heat(Market.HK))
                .thenReturn(List.of(new HeatRow("软件服务", 50.0)));

        HkusCrossSection section = service.rowsFor(RANK_DATE, Market.HK);

        assertThat(section.rows()).hasSize(2);
        PoolRow tencent = section.rows().get(0);
        PoolRow alibaba = section.rows().get(1);
        // A 股事件/资讯被市场裁剪 → HK00700 仅剩 UNKNOWN 成员边（白名单过滤）→ 全维零信号
        assertThat(tencent.fCatalyst()).isZero();
        assertThat(tencent.fConduction()).isZero();
        // SW-only 行业名不出边：HK09988 仅成员边（软件服务 heatNorm 第 1）
        assertThat(alibaba.fConduction()).isEqualTo(round1(100.0 * 0.3 * 30.0 / 30.0));
    }

    @Test
    void rowsFor_emptySubjectPool_returnsEmptyRowsWithoutHeavyReads() {
        when(snapshotRepository.findActiveSubjects(Market.US)).thenReturn(List.of());

        HkusCrossSection section = service.rowsFor(RANK_DATE, Market.US);

        assertThat(section.rows()).isEmpty();
        assertThat(section.params().weightSum()).isEqualTo(0.8); // 0.40+0.20+0.20（F3/F5 置 0）
        verify(snapshotRepository).findActiveSubjects(Market.US);
        verify(snapshotRepository, org.mockito.Mockito.never())
                .findEventsInWindow(anyString(), anyString());
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
