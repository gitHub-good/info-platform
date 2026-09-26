package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
                "COMPUTED_AT_EXEMPT");
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
        assertThat(maotai.breakthrough()).isFalse(); // F1 ≈ 22.5 < 60

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
    void totalAndBreakthrough_placeholderUntilComposerLands() {
        // T170 落层、T171 落合成（ScoreComposer）：本提交 total/breakthrough 占位 0/false，
        // 权重指纹已入行（T171 合成接管后由其验收用例断言真值）
        stubHappyPath();

        service.snapshotAll(SNAPSHOT);

        org.mockito.ArgumentCaptor<List<FactorSnapshotRow>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        for (FactorSnapshotRow row : captor.getValue()) {
            assertThat(row.totalScore()).isZero();
            assertThat(row.breakthrough()).isFalse();
            assertThat(row.weightBasis()).startsWith("vs-v1:");
        }
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
