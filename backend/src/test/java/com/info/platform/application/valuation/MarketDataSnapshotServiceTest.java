package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MarketDataSnapshotService 单测（T170，方案 §4.6 阶段 0 + ADR-0058 裁决 5）：腾讯批量复用（f 键中间结构 → V30 列映射）、
 * 估值缺数口径（"-"/≤0 → NULL）、非 SH/SZ 代码跳过、整段失败降级不抛（四维照算由编排层兜底）。TencentQuoteClient 全 mock 零外呼。
 */
class MarketDataSnapshotServiceTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private QuoteBatchClient client;
    private MarketDailySnapshotRepository repository;
    private MarketDataSnapshotService service;

    @BeforeEach
    void setUp() {
        client = mock(QuoteBatchClient.class);
        repository = mock(MarketDailySnapshotRepository.class);
        service = new MarketDataSnapshotService(client, repository);
        // 符号转换端口语义：SH/SZ/HK 前缀小写化，其余（板块码等）empty（与腾讯实现同构）
        when(client.toTencentSymbol(anyString()))
                .thenAnswer(
                        inv -> {
                            String code = inv.getArgument(0);
                            return code.regionMatches(true, 0, "SH", 0, 2)
                                            || code.regionMatches(true, 0, "SZ", 0, 2)
                                            || code.regionMatches(true, 0, "HK", 0, 2)
                                    ? java.util.Optional.of(code.toLowerCase())
                                    : java.util.Optional.empty();
                        });
    }

    private static Map<String, Object> quoteRow(
            String close,
            String pct,
            String turnover,
            String amplitude,
            String volume,
            String pe,
            String pb) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (close != null) row.put("f43", new BigDecimal(close));
        if (pct != null) row.put("f170", new BigDecimal(pct));
        if (turnover != null) row.put("f168", new BigDecimal(turnover));
        if (amplitude != null) row.put("f171", new BigDecimal(amplitude));
        if (volume != null) row.put("f47", Long.parseLong(volume));
        if (pe != null) row.put("f162", new BigDecimal(pe));
        if (pb != null) row.put("f167", new BigDecimal(pb));
        row.put("f30", "20260922161403");
        return row;
    }

    @Test
    void refresh_mapsQuoteFieldsToColumns() {
        when(client.fetchQuotes(anyList()))
                .thenReturn(
                        Map.of(
                                "sh600519",
                                quoteRow(
                                        "1237.00", "-1.14", "0.25", "2.00", "31239", "17.37",
                                        "6.15")));
        when(repository.upsertAll(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        MarketDataSnapshotService.RefreshReport report =
                service.refresh(
                        SNAPSHOT,
                        List.of(new FactorSnapshotRepository.SubjectRef(1, "SH600519", "贵州茅台")));

        assertThat(report.ok()).isTrue();
        assertThat(report.totalSubjects()).isEqualTo(1);
        assertThat(report.rowsUpserted()).isEqualTo(1);

        ArgumentCaptor<List<MarketDailyRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        MarketDailyRow row = captor.getValue().get(0);
        assertThat(row.subjectId()).isEqualTo(1L);
        assertThat(row.snapshotDate()).isEqualTo("2026-09-22");
        assertThat(row.closePrice()).isEqualTo(1237.0);
        assertThat(row.pctChange()).isEqualTo(-1.14);
        assertThat(row.turnoverRate()).isEqualTo(0.25);
        assertThat(row.amplitude()).isEqualTo(2.0);
        assertThat(row.volume()).isEqualTo(31239.0);
        assertThat(row.peTtm()).isEqualTo(17.37);
        assertThat(row.pb()).isEqualTo(6.15);
        assertThat(row.source()).isEqualTo("tencent");
        assertThat(row.quoteTime()).isEqualTo("20260922161403");
    }

    @Test
    void refresh_missingOrNonPositiveValuation_storedAsNull() {
        // 亏损股 PE 空值（"-" → 客户端不产出）与负 PB → NULL（缺数口径，V30 列注释）；PE 缺 PB 在 → 行仍落
        when(client.fetchQuotes(anyList()))
                .thenReturn(
                        Map.of(
                                "sz000001",
                                quoteRow("11.5", "0.3", "0.4", "1.2", "80000", null, "-0.5")));

        service.refresh(
                SNAPSHOT, List.of(new FactorSnapshotRepository.SubjectRef(2, "SZ000001", "平安银行")));

        ArgumentCaptor<List<MarketDailyRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        MarketDailyRow row = captor.getValue().get(0);
        assertThat(row.peTtm()).isNull();
        assertThat(row.pb()).isNull(); // ≤0 视为缺数
        assertThat(row.closePrice()).isEqualTo(11.5);
    }

    @Test
    void refresh_unmappedCodeSkipped_gracefully() {
        // 板块/指数码（非 SH/SZ/HK 前缀）无腾讯符号 → 跳过不报错
        when(client.fetchQuotes(anyList())).thenReturn(Map.of());

        MarketDataSnapshotService.RefreshReport report =
                service.refresh(
                        SNAPSHOT,
                        List.of(
                                new FactorSnapshotRepository.SubjectRef(1, "SECTOR01", "板块X"),
                                new FactorSnapshotRepository.SubjectRef(2, "SH600519", "贵州茅台")));

        assertThat(report.ok()).isTrue();
        assertThat(report.rowsUpserted()).isZero();
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void refresh_clientFailure_degradesWithoutThrowing() {
        // 整段失败 → 降级报告（不抛——四维照算由编排层继续，§5 降级预案）
        when(client.fetchQuotes(anyList())).thenThrow(new RuntimeException("connect timeout"));

        MarketDataSnapshotService.RefreshReport report =
                service.refresh(
                        SNAPSHOT,
                        List.of(new FactorSnapshotRepository.SubjectRef(1, "SH600519", "贵州茅台")));

        assertThat(report.ok()).isFalse();
        assertThat(report.rowsUpserted()).isZero();
        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void refresh_symbolWithoutQuoteRow_skipped() {
        // 响应行缺席（无效代码/停牌无行）→ 该标的无行情行（次日 NO_MARKET_DATA 口径由因子层判定）
        when(client.fetchQuotes(anyList())).thenReturn(Map.of());

        service.refresh(
                SNAPSHOT, List.of(new FactorSnapshotRepository.SubjectRef(1, "SH600519", "贵州茅台")));

        verify(repository, never()).upsertAll(anyList());
    }

    @Test
    void refresh_batchesByFiftySymbols() {
        // 105 只 → 3 块 ×50（复用客户端 50/块上限，服务层逐块间隔——§3.5 裁决 5.3）
        List<FactorSnapshotRepository.SubjectRef> subjects = new java.util.ArrayList<>();
        for (int i = 0; i < 105; i++) {
            subjects.add(
                    new FactorSnapshotRepository.SubjectRef(
                            i, String.format("SH6%04d", i), "标的" + i));
        }
        when(client.fetchQuotes(anyList())).thenReturn(Map.of());

        service.refresh(SNAPSHOT, subjects);

        verify(client, org.mockito.Mockito.times(3)).fetchQuotes(anyList());
    }
}
