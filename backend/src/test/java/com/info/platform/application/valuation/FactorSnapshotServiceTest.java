package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.HeatRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.ValuationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FactorSnapshotService 编排单测（T170，方案 §4.6 四阶段 + §4.4 幂等口径）：同输入两连跑逐字段零漂移（验收红线）、 行情段失败降级（四维照算 +
 * NO_MARKET_DATA + F5 中性）、ST/无关联/缺估值 flags 如实标注、breakthrough 三阈值、 报告对账计数。仓储与行情服务全
 * mock（纯编排面）；公式正确性归域层单测。
 */
class FactorSnapshotServiceTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-22T09:30:00Z"), ZoneOffset.UTC);

    private FactorSnapshotRepository repository;
    private MarketDailySnapshotRepository marketRepository;
    private MarketDataSnapshotService marketService;
    private FactorSnapshotService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(FactorSnapshotRepository.class);
        marketRepository = mock(MarketDailySnapshotRepository.class);
        marketService = mock(MarketDataSnapshotService.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        when(repository.upsertAllIncremental(anyList(), anyString()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        service =
                new FactorSnapshotService(
                        repository,
                        marketRepository,
                        marketService,
                        new ValuationSettings(configService, objectMapper),
                        objectMapper,
                        CLOCK);
    }

    private List<FactorSnapshotRepository.SubjectRef> subjects() {
        return List.of(
                new FactorSnapshotRepository.SubjectRef(1, "SH600519", "贵州茅台"),
                new FactorSnapshotRepository.SubjectRef(2, "SZ000001", "平安银行"),
                new FactorSnapshotRepository.SubjectRef(3, "SH600003", "*ST金科"),
                new FactorSnapshotRepository.SubjectRef(4, "SZ300750", "宁德时代"));
    }

    private List<FactorSnapshotRepository.EventRef> events() {
        return List.of(
                new FactorSnapshotRepository.EventRef(
                        new ValuationEvent(
                                101L,
                                "业绩预增",
                                SNAPSHOT.minusDays(1),
                                Direction.BULLISH,
                                Importance.HIGH,
                                EventType.EARNINGS_FORECAST),
                        List.of("SH600519"),
                        List.of("食品饮料")),
                new FactorSnapshotRepository.EventRef(
                        new ValuationEvent(
                                102L,
                                "监管立案",
                                SNAPSHOT.minusDays(2),
                                Direction.BEARISH,
                                Importance.HIGH,
                                EventType.REGULATORY_PENALTY),
                        List.of("SZ000001"),
                        List.of("银行")),
                new FactorSnapshotRepository.EventRef(
                        new ValuationEvent(
                                103L,
                                "窗外事件",
                                SNAPSHOT.minusDays(40),
                                Direction.BULLISH,
                                Importance.HIGH,
                                EventType.EARNINGS_FORECAST),
                        List.of("SZ300750"),
                        List.of("电力设备")));
    }

    private Map<Long, MarketDailySnapshotRepository.MarketDailyRow> marketFixture() {
        return Map.of(
                1L,
                new MarketDailySnapshotRepository.MarketDailyRow(
                        1L,
                        "2026-09-22",
                        1237.0,
                        -1.14,
                        0.25,
                        2.0,
                        31239.0,
                        17.37,
                        6.15,
                        "tencent",
                        "20260922161403"),
                2L,
                new MarketDailySnapshotRepository.MarketDailyRow(
                        2L,
                        "2026-09-22",
                        11.5,
                        0.3,
                        0.4,
                        1.2,
                        80000.0,
                        null,
                        0.47,
                        "tencent",
                        "20260922161403"),
                4L,
                new MarketDailySnapshotRepository.MarketDailyRow(
                        4L,
                        "2026-09-22",
                        210.0,
                        1.1,
                        0.6,
                        2.4,
                        120000.0,
                        null,
                        null,
                        "tencent",
                        "20260922161403"));
    }

    private void stubHappyPath() {
        when(repository.findActiveSubjects()).thenReturn(subjects());
        when(repository.findEventsInWindow(anyString(), anyString())).thenReturn(events());
        when(repository.findMatchedNewsInWindow(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new FactorSnapshotRepository.NewsLinkRow(
                                        List.of("SH600519"), "电子", null, SNAPSHOT.minusDays(3))));
        when(repository.findH24Heat())
                .thenReturn(
                        List.of(
                                new HeatRow("银行", 812.4),
                                new HeatRow("电子", 400.0),
                                new HeatRow("食品饮料", 100.0)));
        when(marketRepository.findByDate(anyString())).thenReturn(marketFixture());
        when(marketService.refresh(any(LocalDate.class), anyList()))
                .thenReturn(new MarketDataSnapshotService.RefreshReport(true, 4, 3));
    }

    @Test
    void sameInputsTwoRuns_zeroDrift_fieldByField() {
        stubHappyPath();

        FactorSnapshotService.SnapshotReport first = service.snapshotAll(SNAPSHOT);
        FactorSnapshotService.SnapshotReport second = service.snapshotAll(SNAPSHOT);

        // §6 幂等验收用例：两轮 upsert 行逐字段相等（total/factors/detail JSON 规范化比较；
        // computed_at 为计算时刻非输入时刻，显式豁免——§4.1 列注记）
        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).upsertAll(captor.capture());
        List<FactorSnapshotRow> firstRows = captor.getAllValues().get(0);
        List<FactorSnapshotRow> secondRows = captor.getAllValues().get(1);

        assertThat(firstRows).hasSameSizeAs(secondRows);
        for (int i = 0; i < firstRows.size(); i++) {
            assertThat(normalize(firstRows.get(i))).isEqualTo(normalize(secondRows.get(i)));
        }
        assertThat(first.snapshotRows()).isEqualTo(second.snapshotRows());
        assertThat(first.coverageRate()).isEqualTo(second.coverageRate());
        assertThat(first.flagCounts()).isEqualTo(second.flagCounts());
        assertThat(firstRows.get(0).totalScore()).isEqualTo(secondRows.get(0).totalScore());
    }

    /** 幂等比较口径：豁免 computed_at（计算时刻），其余字段（含 detail/flags JSON 文本）全等。 */
    private static FactorSnapshotRow normalize(FactorSnapshotRow row) {
        return new FactorSnapshotRow(
                row.subjectId(),
                row.snapshotDate(),
                row.fCatalyst(),
                row.fConduction(),
                row.fFundamental(),
                row.fRisk(),
                row.fValuation(),
                row.totalScore(),
                row.breakthrough(),
                row.factorDetailJson(),
                row.dataFlagsJson(),
                row.weightBasis(),
                "COMPUTED_AT_EXEMPT",
                row.lastEventDate());
    }

    @Test
    void rowsCarryFormulaOutputsAndFlags() {
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        List<FactorSnapshotRow> rows = captor.getValue();
        FactorSnapshotRow maotai = rowOf(rows, 1);
        FactorSnapshotRow pingan = rowOf(rows, 2);
        FactorSnapshotRow st = rowOf(rows, 3);
        FactorSnapshotRow catl = rowOf(rows, 4);

        // 茅台：事件 101（age1 衰减 0.5^0.2≈0.8706）→ raw1≈0.8706 → F1≈22.5；PE 全场唯一正值 → pct0 → F5 100
        assertThat(maotai.fCatalyst()).isGreaterThan(0.0).isLessThan(25.0);
        assertThat(maotai.fFundamental()).isGreaterThan(50.0);
        assertThat(maotai.fRisk()).isEqualTo(100.0);
        assertThat(maotai.fValuation()).isEqualTo(100.0);
        assertThat(flags(maotai)).isEmpty();
        assertThat(maotai.breakthrough())
                .as("F1≈22.5 > btCatalystMin=20（M21 T180 校准）且 F2/F4 达标 → 突破")
                .isTrue();

        // 平安：BEARISH 处罚（age2）→ 1.5×10×0.5^0.4≈11.37 → F4≈88.6；PE 缺 → PB 回退链最低 → F5 100
        assertThat(pingan.fRisk()).isGreaterThan(80.0).isLessThan(100.0);
        assertThat(pingan.fValuation()).isEqualTo(100.0);
        assertThat(flags(pingan)).isEmpty();

        // *ST：无行情行 → NO_MARKET_DATA + F5 中性 50；名称含 ST → ST_RISK + F4=60；无关联 → NO_ASSOC_INDUSTRY
        assertThat(st.fValuation()).isEqualTo(50.0);
        assertThat(st.fRisk()).isEqualTo(60.0);
        assertThat(flags(st)).containsExactly("NO_MARKET_DATA", "NO_ASSOC_INDUSTRY", "ST_RISK");

        // 宁德：行情行在但 PE/PB 双缺 → NO_VALUATION_DATA + F5 50；无窗内事件无回联 → F1=0/F2=0/F3=50
        assertThat(catl.fValuation()).isEqualTo(50.0);
        assertThat(catl.fConduction()).isZero();
        assertThat(catl.fCatalyst()).isZero();
        assertThat(catl.fFundamental()).isEqualTo(50.0);
        assertThat(flags(catl)).containsExactly("NO_ASSOC_INDUSTRY", "NO_VALUATION_DATA");
    }

    @Test
    void lastEventDate_carriedOnRows_maxOfEvidenceEntries_nullWhenNoEvents() {
        // M21 §4.1.6：三维护据事件最大 eventDate（粗筛四键次级排序 + 榜单卡双用途）
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        List<FactorSnapshotRow> rows = captor.getValue();

        // 茅台：catalyst 事件 101（eventDate=SNAPSHOT-1）→ last_event_date 快照前一日
        assertThat(rowOf(rows, 1).lastEventDate()).isEqualTo(SNAPSHOT.minusDays(1).toString());
        // 平安：risk 维事件 102（eventDate=SNAPSHOT-2）
        assertThat(rowOf(rows, 2).lastEventDate()).isEqualTo(SNAPSHOT.minusDays(2).toString());
        // 宁德：窗内零事件（窗外事件被 W1 剔除）→ NULL
        assertThat(rowOf(rows, 4).lastEventDate()).isNull();
    }

    @Test
    void industryMembers_pathC_memberOnlySubjectGetsConduction() {
        // M21 六输入扩位（ADR-0059 裁决 1）：行业成员投影 → swPrimaryOf 映射 → 路 C 成员边 → 纯成员标的 F2 覆盖
        stubHappyPath();
        when(repository.findIndustryMembers())
                .thenReturn(
                        List.of(
                                new FactorSnapshotRepository.IndustryMemberRow(
                                        "SZ300750", "消费电子"), // 命中映射 → 电子
                                new FactorSnapshotRepository.IndustryMemberRow(
                                        "SH600003", "不存在的板块"))); // 未收录 → 投影层过滤（安全侧失败）

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        List<FactorSnapshotRow> rows = captor.getValue();

        // 宁德：电子成员边（heat 400 名次 2 → heatNorm=(31-2)/30）× weight 0.3 × decay(0)=1 → F2 = 29.0
        FactorSnapshotRow catl = rowOf(rows, 4);
        assertThat(catl.fConduction()).isGreaterThan(28.0).isLessThan(30.0);
        assertThat(flags(catl)).containsExactly("NO_VALUATION_DATA"); // NO_ASSOC_INDUSTRY 消除

        // ST 标的板块未收录 → 无成员边 → NO_ASSOC 如实标注不隐藏（沿 M20 口径）
        assertThat(flags(rowOf(rows, 3))).contains("NO_ASSOC_INDUSTRY");
    }

    @Test
    void detailJson_carriesTraceEntries() throws Exception {
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        FactorSnapshotRow maotai = rowOf(captor.getValue(), 1);
        JsonNode detail = objectMapper.readTree(maotai.factorDetailJson());

        // §4.5 契约：五维明细，catalyst.entries 带 eventId（trace-v1 下钻原料）
        assertThat(detail.path("catalyst").path("entries").size()).isEqualTo(1);
        assertThat(detail.path("catalyst").path("entries").get(0).path("eventId").asLong())
                .isEqualTo(101L);
        assertThat(detail.path("catalyst").path("raw").asDouble()).isGreaterThan(0.0);
        // 窗外事件（103，age40）不进任何维明细
        assertThat(detail.toString()).doesNotContain("103");
        // conduction：双路关联（食品饮料 EVENT + 电子 NEWS_MAIN）落行业与热度值
        assertThat(detail.path("conduction").path("assoc").size()).isEqualTo(2);
        // risk：无 ST 无利空
        assertThat(detail.path("risk").path("stFlag").asBoolean()).isFalse();
        assertThat(detail.path("risk").path("eventPenalty").asDouble()).isZero();
        // valuation：PE 基准与值
        assertThat(detail.path("valuation").path("basis").asText()).isEqualTo("PE");
        assertThat(detail.path("valuation").path("pe").asDouble()).isEqualTo(17.37);
    }

    @Test
    void marketRefreshFailure_degradesWithoutBlocking() {
        // §4.6 阶段 0：整段失败不中止——WARN 留痕 + 四维照算 + F5 缺数中性 + NO_MARKET_DATA
        when(repository.findActiveSubjects()).thenReturn(subjects());
        when(repository.findEventsInWindow(anyString(), anyString())).thenReturn(events());
        when(repository.findMatchedNewsInWindow(anyString(), anyString())).thenReturn(List.of());
        when(repository.findH24Heat()).thenReturn(List.of());
        when(marketRepository.findByDate(anyString())).thenReturn(Map.of());
        when(marketService.refresh(any(LocalDate.class), anyList()))
                .thenReturn(new MarketDataSnapshotService.RefreshReport(false, 4, 0));

        FactorSnapshotService.SnapshotReport report = service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        assertThat(captor.getValue()).hasSize(4); // 四维 Must 链零依赖行情
        for (FactorSnapshotRow row : captor.getValue()) {
            assertThat(row.fValuation()).isEqualTo(50.0); // F5 缺数中性
            assertThat(flags(row)).contains("NO_MARKET_DATA");
        }
        assertThat(report.marketDataRows()).isZero();
        assertThat(report.flagCounts().get("NO_MARKET_DATA")).isEqualTo(4L);
    }

    @Test
    void composedTotal_weightedMeanOfFiveFactors() {
        // T171 ScoreComposer：total = Σ w×F / Σw（F5 权重 0 不进基线——茅台 F5=100 不抬分）
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        FactorSnapshotRow maotai = rowOf(captor.getValue(), 1);

        // 0.4×22.5 + 0.2×91.2 + 0.2×76.1 + 0.2×100 = 62.46 → 62.5（round1）
        assertThat(maotai.totalScore()).isCloseTo(62.5, org.assertj.core.data.Offset.offset(0.05));
        // M21 T180 校准后：F1≈22.5 ≥ 20 且 F2≈91 ≥ 50、F4=100 ≥ 80 → 「一条强利好即突破候选」为真
        assertThat(maotai.breakthrough()).isTrue();
        assertThat(maotai.weightBasis()).startsWith("vs-v1:");
    }

    @Test
    void breakthroughRequiresAllThreeThresholds() {
        // 10 条 HIGH BULLISH 业绩事件（age0）+ 高热度行业回联 → F1≈76.9/F2=100/F4=100 → 标签真
        when(repository.findActiveSubjects())
                .thenReturn(
                        List.of(new FactorSnapshotRepository.SubjectRef(9, "SH999999", "突破标的")));
        List<FactorSnapshotRepository.EventRef> burst = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            burst.add(
                    new FactorSnapshotRepository.EventRef(
                            new ValuationEvent(
                                    i,
                                    "业绩预增" + i,
                                    SNAPSHOT,
                                    Direction.BULLISH,
                                    Importance.HIGH,
                                    EventType.EARNINGS_FORECAST),
                            List.of("SH999999"),
                            List.of()));
        }
        when(repository.findEventsInWindow(anyString(), anyString())).thenReturn(burst);
        when(repository.findMatchedNewsInWindow(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new FactorSnapshotRepository.NewsLinkRow(
                                        List.of("SH999999"), "银行", null, SNAPSHOT)));
        when(repository.findH24Heat()).thenReturn(List.of(new HeatRow("银行", 100.0)));
        when(marketRepository.findByDate(anyString())).thenReturn(Map.of());
        when(marketService.refresh(any(LocalDate.class), anyList()))
                .thenReturn(new MarketDataSnapshotService.RefreshReport(true, 1, 0));

        service.snapshotAll(SNAPSHOT);

        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        FactorSnapshotRow row = captor.getValue().get(0);

        assertThat(row.fCatalyst()).isCloseTo(76.9, org.assertj.core.data.Offset.offset(0.01));
        assertThat(row.fConduction()).isEqualTo(100.0);
        assertThat(row.fRisk()).isEqualTo(100.0);
        // total = 0.4×76.9 + 0.2×100 + 0.2×100 + 0.2×100 = 90.77 → 90.8
        assertThat(row.totalScore()).isCloseTo(90.8, org.assertj.core.data.Offset.offset(0.05));
        assertThat(row.breakthrough()).isTrue();
        // 缺行情仍如实标注（标签不豁免 flags）
        assertThat(flags(row)).contains("NO_MARKET_DATA");
    }

    @Test
    void reportCarriesReconciliationCounts() {
        stubHappyPath();

        FactorSnapshotService.SnapshotReport report = service.snapshotAll(SNAPSHOT);

        assertThat(report.activeSubjects()).isEqualTo(4);
        assertThat(report.snapshotRows()).isEqualTo(4);
        assertThat(report.marketDataRows()).isEqualTo(3);
        assertThat(report.coverageRate()).isEqualTo(100.0);
        assertThat(report.flagCounts())
                .containsEntry("NO_MARKET_DATA", 1L)
                .containsEntry("ST_RISK", 1L)
                .containsEntry("NO_ASSOC_INDUSTRY", 2L); // *ST 与宁德无关联
        assertThat(report.detail())
                .contains("subjects=4")
                .contains("rows=4")
                .contains("coverage=100.0")
                .contains("market=3");
    }

    // ---- M22 T190 recomputeIncremental：与全量同源投影同纯函数零漂移（方案 §3.2） ----

    @Test
    void recomputeIncremental_zeroDriftAgainstFullSnapshot_fieldByField() {
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);
        FactorSnapshotService.IncrementalReport report =
                service.recomputeIncremental(SNAPSHOT, java.util.Set.of(1L, 2L));

        // 同投影 + 同 rowOf 纯函数 + 同 weight_basis：增量行与全量行逐字段相等（验收场景 3 构造面）
        ArgumentCaptor<List<FactorSnapshotRow>> fullCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(fullCaptor.capture());
        ArgumentCaptor<List<FactorSnapshotRow>> incrementalCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAllIncremental(incrementalCaptor.capture(), anyString());
        List<FactorSnapshotRow> incrementalRows = incrementalCaptor.getValue();
        assertThat(incrementalRows).hasSize(2);
        for (FactorSnapshotRow incremental : incrementalRows) {
            FactorSnapshotRow full = rowOf(fullCaptor.getValue(), incremental.subjectId());
            assertThat(normalize(incremental)).isEqualTo(normalize(full));
            assertThat(incremental.weightBasis()).isEqualTo(full.weightBasis());
        }
        assertThat(report.upsertedRows()).isEqualTo(2);
        assertThat(report.incrementAtIso()).isNotBlank();
    }

    @Test
    void recomputeIncremental_onlyTargetRowsWithoutMarketRefresh() {
        stubHappyPath();

        FactorSnapshotService.IncrementalReport report =
                service.recomputeIncremental(SNAPSHOT, java.util.Set.of(3L));

        // 集外不触碰（写入范围 = 受影响集）+ 增量不拉行情（行情面属 17:30 全量职责，快照日守卫不变）
        verify(marketService, never()).refresh(any(LocalDate.class), anyList());
        ArgumentCaptor<List<FactorSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAllIncremental(captor.capture(), anyString());
        assertThat(captor.getValue()).extracting(FactorSnapshotRow::subjectId).containsExactly(3L);
        assertThat(report.upsertedRows()).isEqualTo(1);
    }

    @Test
    void recomputeIncremental_emptyTargets_noWrite() {
        stubHappyPath();

        FactorSnapshotService.IncrementalReport report =
                service.recomputeIncremental(SNAPSHOT, java.util.Set.of());

        verify(repository, never()).upsertAllIncremental(anyList(), anyString());
        assertThat(report.upsertedRows()).isZero();
    }

    private static FactorSnapshotRow rowOf(List<FactorSnapshotRow> rows, long subjectId) {
        return rows.stream().filter(r -> r.subjectId() == subjectId).findFirst().orElseThrow();
    }

    private List<String> flags(FactorSnapshotRow row) {
        try {
            return objectMapper.readValue(row.dataFlagsJson(), List.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
