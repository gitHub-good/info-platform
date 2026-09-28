package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.mainline.BoardQuote;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.HistoryPctDay;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.mainline.IndustryQuote;
import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.domain.mainline.LeaderStock;
import com.info.platform.domain.push.SourceAlertEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * IndustryMarketSnapshotService 单测（M27 T242，方案 §4.2.3 + ADR-0063 裁决 1/3——§6 测试要点）：双通道降级（A 失败切 B /
 * 双败 no-op 不动旧快照）/ pct_d5 库内自算（近 5 交易日复利，冷启动 NULL）/ 通道 B 直给 / 连续 5 轮双败告警一次 + 恢复复位 / 轮次统计留痕。纯
 * Mockito 单测（零外呼零 DB——仓储与双通道全 Mock）。
 */
class IndustryMarketSnapshotServiceTest {

    /** 固定时钟：2026-09-28T15:00:02+08:00（Asia/Shanghai 当日 = 2026-09-28）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T07:00:02Z"), ZoneOffset.UTC);

    private IndustryQuoteSource eastmoney;

    private IndustryQuoteSource tencent;

    private IndustryMarketSnapshotRepository repository;

    private ApplicationEventPublisher publisher;

    private IndustryMarketSnapshotService service;

    @BeforeEach
    void setUp() {
        eastmoney = mock(IndustryQuoteSource.class);
        tencent = mock(IndustryQuoteSource.class);
        repository = mock(IndustryMarketSnapshotRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service =
                new IndustryMarketSnapshotService(
                        eastmoney, tencent, repository, publisher, FIXED_CLOCK);
    }

    private static IndustryQuoteBatch eastmoneyBatch() {
        // 通道 A：板块 2 行（电子两板块聚合 1.625%）+ 行业行 2（pctD5 空——服务层自算）
        BoardQuote semi = new BoardQuote("半导体", "电子", 2.5, 120, 40, 3e9, 3e12);
        BoardQuote consumer = new BoardQuote("消费电子", "电子", -1.0, 40, 160, -2e9, 1e12);
        IndustryQuote electronics =
                new IndustryQuote("电子", 1.625, null, 160, 200, 1e9, 4e12, null, "CAP_WEIGHTED");
        IndustryQuote bank =
                new IndustryQuote("银行", 0.5, null, 30, 12, 1e9, 9.5e12, null, "CAP_WEIGHTED");
        return new IndustryQuoteBatch(
                "eastmoney-push2",
                "2026-09-28T15:00:02+08:00",
                List.of(semi, consumer),
                List.of(electronics, bank),
                1);
    }

    @Test
    void run_channelASuccess_upsertsBoardAndIndustryRowsWithSelfComputedPctD5() {
        // Arrange：近 5 交易日历史（今日 + 前 4 日）→ 电子 pct_d5 = 1.0×1.0×1.01×1.02×1.01625 − 1
        when(eastmoney.fetch()).thenReturn(eastmoneyBatch());
        when(repository.recentSnapshotDates(5))
                .thenReturn(List.of("2026-09-21", "2026-09-24", "2026-09-25", "2026-09-27"));
        when(repository.findIndustryPctDayForDates(anyList()))
                .thenReturn(
                        List.of(
                                new HistoryPctDay("2026-09-21", "电子", 0.0),
                                new HistoryPctDay("2026-09-24", "电子", 0.0),
                                new HistoryPctDay("2026-09-25", "电子", 1.0),
                                new HistoryPctDay("2026-09-27", "电子", 2.0),
                                new HistoryPctDay("2026-09-21", "银行", 0.5),
                                new HistoryPctDay("2026-09-24", "银行", -0.5),
                                new HistoryPctDay("2026-09-25", "银行", 0.5),
                                new HistoryPctDay("2026-09-27", "银行", 0.5)));

        // Act
        when(repository.upsertAll(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        IndustryMarketSnapshotService.SnapshotReport report = service.run();

        // Assert：板块行 + 行业行同批 UPSERT；电子 pct_d5 = [(1.01×1.02×1.01625) − 1]×100 ≈ 4.686%
        assertThat(report.snapshotRows()).isEqualTo(4);
        assertThat(report.detail()).contains("source=eastmoney-push2").contains("unmappedWarn=1");
        ArgumentCaptor<List<MarketSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        List<MarketSnapshotRow> rows = captor.getValue();
        assertThat(rows).hasSize(4);
        MarketSnapshotRow electronics =
                rows.stream()
                        .filter(r -> r.rowType().equals("INDUSTRY") && r.dimName().equals("电子"))
                        .findFirst()
                        .orElseThrow();
        assertThat(electronics.pctDay()).isEqualTo(1.625);
        assertThat(electronics.pctD5())
                .isCloseTo(
                        ((1.01 * 1.02 * 1.01625) - 1) * 100,
                        org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rows.stream().filter(r -> r.rowType().equals("BOARD")).count()).isEqualTo(2);
        assertThat(
                        rows.stream()
                                .filter(r -> r.rowType().equals("BOARD"))
                                .allMatch(r -> r.pctD5() == null))
                .isTrue();
    }

    @Test
    void run_channelAFailure_switchesToTencentSameRound() {
        // Arrange：通道 A 重试耗尽 → 当轮切 B（轮级互切，成本 = 1 次请求）；B 的 pct_d5 源直给
        when(eastmoney.fetch()).thenThrow(new IllegalStateException("push2 封禁期 Empty reply"));
        when(tencent.fetch())
                .thenReturn(
                        new IndustryQuoteBatch(
                                "tencent-rank",
                                "2026-09-28T15:00:02+08:00",
                                List.of(),
                                List.of(
                                        new IndustryQuote(
                                                "食品饮料",
                                                -0.17,
                                                -0.82,
                                                57,
                                                122,
                                                -16835.63e4,
                                                36103.24e8,
                                                new LeaderStock("601579", "会稽山", 9.99),
                                                "TENCENT_DIRECT")),
                                0));

        // Act
        IndustryMarketSnapshotService.SnapshotReport report = service.run();

        // Assert：B 通道落库（source/aggMethod/leaderStock 直存），无 pct_d5 自算查询
        assertThat(report.detail()).contains("source=tencent-rank").contains("pctD5=tencent");
        verify(repository, never()).recentSnapshotDates(anyInt());
        ArgumentCaptor<List<MarketSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        MarketSnapshotRow food = captor.getValue().get(0);
        assertThat(food.source()).isEqualTo("tencent-rank");
        assertThat(food.aggMethod()).isEqualTo("TENCENT_DIRECT");
        assertThat(food.pctD5()).isEqualTo(-0.82);
        assertThat(food.leaderStockJson()).contains("601579").contains("会稽山");
    }

    @Test
    void run_coldStartInsufficientHistory_pctD5StaysNull() {
        // Arrange：仅 2 个历史日（< 4 前置日）→ 冷启动该维 NULL（榜单缺维降权标注承接）
        when(eastmoney.fetch()).thenReturn(eastmoneyBatch());
        when(repository.recentSnapshotDates(5)).thenReturn(List.of("2026-09-27"));
        when(repository.findIndustryPctDayForDates(anyList()))
                .thenReturn(List.of(new HistoryPctDay("2026-09-27", "电子", 1.0)));

        // Act
        service.run();

        // Assert
        ArgumentCaptor<List<MarketSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        MarketSnapshotRow electronics =
                captor.getValue().stream()
                        .filter(r -> r.rowType().equals("INDUSTRY"))
                        .findFirst()
                        .orElseThrow();
        assertThat(electronics.pctD5()).isNull();
    }

    @Test
    void run_bothChannelsFail_noopKeepsOldSnapshot_countsAndAlertsAtThreshold() {
        // Arrange：双通道全败 → 本轮 no-op（不 upsert——旧快照原样保留）
        when(eastmoney.fetch()).thenThrow(new IllegalStateException("A down"));
        when(tencent.fetch()).thenThrow(new IllegalStateException("B down"));

        // Act：4 轮失败（未达阈值 5）
        for (int i = 0; i < 4; i++) {
            IndustryMarketSnapshotService.SnapshotReport report = service.run();
            assertThat(report.snapshotRows()).isZero();
            assertThat(report.detail()).contains("双通道失败");
        }
        verify(repository, never()).upsertAll(anyList());
        verify(publisher, never()).publishEvent(any());

        // Act：第 5 轮 → 告警一次（文案标注 INDUSTRY_MARKET_SNAPSHOT）
        service.run();
        ArgumentCaptor<SourceAlertEvent> alertCaptor =
                ArgumentCaptor.forClass(SourceAlertEvent.class);
        verify(publisher, times(1)).publishEvent(alertCaptor.capture());
        assertThat(alertCaptor.getValue().sourceCode()).isEqualTo("INDUSTRY_MARKET_SNAPSHOT");
        assertThat(alertCaptor.getValue().kind()).isEqualTo(SourceAlertEvent.Kind.ALERT);
        assertThat(alertCaptor.getValue().consecutiveFailures()).isEqualTo(5);

        // Act：第 6 轮仍失败 → 恢复前不重复告警
        service.run();
        verify(publisher, times(1)).publishEvent(any(SourceAlertEvent.class));
    }

    @Test
    void run_successAfterFailures_resetsCounter() {
        // Arrange：2 轮失败 → 成功 → 再 4 轮失败（计数从 0 重起，不触发告警）。
        // 重打桩走 doThrow/doReturn（活跃 thenThrow 桩上 when() 会真调用 mock 抛出）
        doThrow(new IllegalStateException("A down")).when(eastmoney).fetch();
        doThrow(new IllegalStateException("B down")).when(tencent).fetch();
        service.run();
        service.run();
        doReturn(
                        new IndustryQuoteBatch(
                                "tencent-rank",
                                "2026-09-28T15:00:02+08:00",
                                List.of(),
                                List.of(
                                        new IndustryQuote(
                                                "电子",
                                                1.0,
                                                0.5,
                                                1,
                                                1,
                                                0d,
                                                1e12,
                                                null,
                                                "TENCENT_DIRECT")),
                                0))
                .when(tencent)
                .fetch();
        when(repository.upsertAll(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        assertThat(service.run().snapshotRows()).isEqualTo(1);
        doThrow(new IllegalStateException("B down")).when(tencent).fetch();
        for (int i = 0; i < 4; i++) {
            service.run();
        }

        // Assert：2 + 4 未连续达 5 → 无告警；成功轮计数清零留痕
        verify(publisher, never()).publishEvent(any(SourceAlertEvent.class));
    }

    @Test
    void run_sameInputTwice_identicalRows_idempotentByUniqueKey() {
        // 幂等红线（REQ 故事 1 场景 3）：同输入两轮 → 载荷逐行相等（UNIQUE 兜底零重写）
        when(eastmoney.fetch()).thenReturn(eastmoneyBatch());
        when(repository.recentSnapshotDates(5)).thenReturn(List.of());
        when(repository.findIndustryPctDayForDates(anyList())).thenReturn(List.of());
        when(repository.upsertAll(anyList())).thenReturn(4);

        service.run();
        service.run();

        ArgumentCaptor<List<MarketSnapshotRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).upsertAll(captor.capture());
        List<String> first =
                captor.getAllValues().get(0).stream()
                        .map(MarketSnapshotRow::toString)
                        .collect(Collectors.toList());
        List<String> second =
                captor.getAllValues().get(1).stream()
                        .map(MarketSnapshotRow::toString)
                        .collect(Collectors.toList());
        assertThat(first).isEqualTo(second);
    }
}
