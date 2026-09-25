package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * HeatSnapshotService 单测（T123，方案 §4.5）：双窗 31×2 行 UPSERT（62 行常驻零填）、prev 等长窗口对齐、环比 deltaPct、basis
 * 串随参数、重跑幂等收敛。 mock 仓储 + 缺省参数。AAA 结构。
 */
class HeatSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private HeatSnapshotRepository repository;
    private HeatSnapshotService service;

    @BeforeEach
    void setUp() {
        repository = mock(HeatSnapshotRepository.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        service =
                new HeatSnapshotService(
                        repository,
                        new PipelineSettings(configService, new ObjectMapper()),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HeatSnapshotRepository.WindowItem windowItem(
            String main, String publishedAtIso) {
        return new HeatSnapshotRepository.WindowItem(
                main, Instant.parse(publishedAtIso), null, null);
    }

    @Test
    void snapshotAll_upsertsSixtyTwoRowsWithZeroFill() {
        // Arrange：当前窗 1 条银行、prev 窗 2 条银行（无事件 → score 2）
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString()),
                        eq(NOW.toString())))
                .thenReturn(List.of(windowItem("银行", "2026-09-22T07:00:00Z")));
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(48)).toString()),
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString())))
                .thenReturn(
                        List.of(
                                windowItem("银行", "2026-09-21T10:00:00Z"),
                                windowItem("银行", "2026-09-21T12:00:00Z")));

        // Act
        HeatSnapshotService.SnapshotReport report = service.snapshotAll();

        // Assert：62 行（31×2）、零行业沉底常驻、报告留痕
        ArgumentCaptor<List<IndustryHeatSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).upsertAll(captor.capture());
        List<IndustryHeatSnapshot> rows = captor.getValue();
        assertThat(rows).hasSize(62);
        assertThat(rows.stream().filter(r -> r.getWindow() == HeatWindow.H24)).hasSize(31);
        assertThat(rows.stream().filter(r -> r.getWindow() == HeatWindow.D7)).hasSize(31);
        assertThat(report.rows()).isEqualTo(62);
        assertThat(report.detail()).contains("h24").contains("d7");
    }

    @Test
    void snapshotAll_deltaPctAgainstAlignedPrevWindow() {
        // prev 窗 = [end-2L, end-L) 等长对齐错位：当前 1 条（07:00，age 1h → 0.5^(1/12)）；
        // prev 2 条（09-21T02:00 age 6h → 0.5^(6/12)=0.7071、09-20T20:00 age 12h → 0.5）
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString()),
                        eq(NOW.toString())))
                .thenReturn(List.of(windowItem("银行", "2026-09-22T07:00:00Z")));
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(48)).toString()),
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString())))
                .thenReturn(
                        List.of(
                                windowItem("银行", "2026-09-21T02:00:00Z"),
                                windowItem("银行", "2026-09-20T20:00:00Z")));
        double prevScore = Math.pow(0.5, 6.0 / 12.0) + Math.pow(0.5, 12.0 / 12.0);
        double currentScore = Math.pow(0.5, 1.0 / 12.0);

        service.snapshotAll();

        ArgumentCaptor<List<IndustryHeatSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        IndustryHeatSnapshot h24Bank =
                captor.getValue().stream()
                        .filter(
                                r ->
                                        r.getWindow() == HeatWindow.H24
                                                && r.getIndustry().equals("银行"))
                        .findFirst()
                        .orElseThrow();
        assertThat(h24Bank.getHeatScore()).isCloseTo(currentScore, within(1e-9));
        assertThat(h24Bank.getPrevScore()).isCloseTo(prevScore, within(1e-9));
        assertThat(h24Bank.getDeltaPct())
                .isCloseTo((currentScore - prevScore) / prevScore * 100.0, within(1e-9));
        assertThat(h24Bank.getNewsCount()).isEqualTo(1);
        assertThat(h24Bank.getEventCount()).isZero();
        assertThat(h24Bank.getBasis()).isEqualTo("heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h");
        assertThat(h24Bank.getSnapshotAt()).isEqualTo(NOW);
    }

    @Test
    void snapshotAll_prevZeroScorePositive_delta100() {
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString()),
                        eq(NOW.toString())))
                .thenReturn(List.of(windowItem("银行", "2026-09-22T07:00:00Z")));
        when(repository.findWindowItems(
                        eq(NOW.minus(java.time.Duration.ofHours(48)).toString()),
                        eq(NOW.minus(java.time.Duration.ofHours(24)).toString())))
                .thenReturn(List.of());

        service.snapshotAll();

        ArgumentCaptor<List<IndustryHeatSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        IndustryHeatSnapshot h24Bank =
                captor.getValue().stream()
                        .filter(
                                r ->
                                        r.getWindow() == HeatWindow.H24
                                                && r.getIndustry().equals("银行"))
                        .findFirst()
                        .orElseThrow();
        assertThat(h24Bank.getDeltaPct()).isEqualTo(100.0);
        IndustryHeatSnapshot h24Idle =
                captor.getValue().stream()
                        .filter(
                                r ->
                                        r.getWindow() == HeatWindow.H24
                                                && r.getIndustry().equals("钢铁"))
                        .findFirst()
                        .orElseThrow();
        assertThat(h24Idle.getHeatScore()).isZero();
        assertThat(h24Idle.getDeltaPct()).isZero(); // 双 0 记 0
    }

    @Test
    void snapshotAll_eventWeightsCounted() {
        // 事件加权：银行 1 条 HIGH 事件（affected 含 房地产）→ 银行 11、房地产 +10
        when(repository.findWindowItems(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new HeatSnapshotRepository.WindowItem(
                                        "银行",
                                        Instant.parse("2026-09-22T08:00:00Z"),
                                        Importance.HIGH,
                                        List.of("银行", "房地产"))))
                .thenReturn(List.of());

        service.snapshotAll();

        ArgumentCaptor<List<IndustryHeatSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        Map<String, IndustryHeatSnapshot> h24 =
                captor.getValue().stream()
                        .filter(r -> r.getWindow() == HeatWindow.H24)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        IndustryHeatSnapshot::getIndustry, r -> r));
        assertThat(h24.get("银行").getHeatScore()).isEqualTo(11.0);
        assertThat(h24.get("房地产").getHeatScore()).isEqualTo(10.0);
        assertThat(h24.get("银行").getEventCount()).isEqualTo(1);
        assertThat(h24.get("房地产").getEventCount()).isEqualTo(1);
    }

    @Test
    void snapshotAll_rerunIdempotentConvergesToCurrentValues() {
        // 幂等：同一数据重跑 → UPSERT 同 62 行，值收敛（重算相等断言的数据面）
        when(repository.findWindowItems(anyString(), anyString())).thenReturn(List.of());

        service.snapshotAll();
        service.snapshotAll();

        verify(repository, times(2)).upsertAll(any());
    }
}
