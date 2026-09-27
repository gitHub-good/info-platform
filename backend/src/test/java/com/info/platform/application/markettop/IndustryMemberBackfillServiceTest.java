package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.markettop.IndustryBoardSource.IndustryBoardRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IndustryMemberBackfillService 编排单测（M21 T180，方案 §4.1.2 + ADR-0059 裁决 1②）： 预检达标零外呼 /
 * 双通道执行序（datacenter 先行，clist 仅在仍低于阈值时机主义）/ 只补 NULL 幂等（两连跑二轮零写入）/ 任一通道失败 WARN 降级不阻塞 / 6 位代码补前缀。 全
 * Mock 零外呼（04 测试规范红线）。
 */
class IndustryMemberBackfillServiceTest {

    private IndustryMemberStore store;
    private IndustryBoardSource datacenterBoards;
    private SubjectListSource clistSource;
    private IndustryMemberBackfillService service;

    @BeforeEach
    void setUp() {
        store = mock(IndustryMemberStore.class);
        datacenterBoards = mock(IndustryBoardSource.class);
        clistSource = mock(SubjectListSource.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        service =
                new IndustryMemberBackfillService(
                        store,
                        datacenterBoards,
                        clistSource,
                        new MarketTopConfigSettings(configService, new ObjectMapper()));
    }

    private void coverage(long withIndustry, long active) {
        when(store.countActiveAShares()).thenReturn(active);
        when(store.countActiveASharesWithIndustry()).thenReturn(withIndustry);
    }

    @Test
    void precheckAboveFloor_zeroExternalCalls_skipped() {
        // Arrange：覆盖率 0.9 ≥ 缺省阈值 0.80
        coverage(4699L, 5221L);

        // Act
        IndustryMemberBackfillService.BackfillReport report = service.backfillIfBelowFloor();

        // Assert：零外呼零写入（attempted=false）
        assertThat(report.attempted()).isFalse();
        assertThat(report.datacenterUpdated()).isZero();
        assertThat(report.clistUpdated()).isZero();
        assertThat(report.coverageRate()).isGreaterThan(0.9).isLessThan(0.91);
        verifyNoInteractions(datacenterBoards);
        verifyNoInteractions(clistSource);
        verify(store, never()).backfillIndustryIfAbsent(anyString(), anyString());
    }

    @Test
    void belowFloor_datacenterFirst_onlyNullRowsBackfilled() {
        // Arrange：5221 只全 NULL（现状实测口径）
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenReturn(
                        List.of(
                                new IndustryBoardRow("600519", "白酒Ⅱ"),
                                new IndustryBoardRow("000001", "银行Ⅱ"),
                                new IndustryBoardRow("300024", "通用设备")));
        when(store.backfillIndustryIfAbsent(anyString(), anyString())).thenReturn(1);

        // Act
        IndustryMemberBackfillService.BackfillReport report = service.backfillIfBelowFloor();

        // Assert：通道 A 执行（6 位代码补前缀：600519→SH / 000001、300024→SZ）
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(store, times(3)).backfillIndustryIfAbsent(codes.capture(), anyString());
        assertThat(codes.getAllValues()).containsExactly("SH600519", "SZ000001", "SZ300024");
        assertThat(report.datacenterUpdated()).isEqualTo(3);
        assertThat(report.attempted()).isTrue();
        // datacenter 后仍低于阈值 → clist 机会通道也尝试（此处未打标 → 抛异常被吞，clistUpdated=0）
        verify(clistSource).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(report.clistUpdated()).isZero();
    }

    @Test
    void datacenterFailure_degradesContinue_clistStillAttempted() {
        // Arrange：通道 A 整段失败（弹性红线：WARN 降级继续，缺成员照跑）
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenThrow(new IllegalStateException("respredict count 完整性校验失败"));
        when(clistSource.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(
                        List.of(
                                new SubjectSnapshot(
                                        "SH600519",
                                        "贵州茅台",
                                        "白酒Ⅱ",
                                        "1.600519",
                                        MarketSyncSpec.A_SHARE_STOCK),
                                new SubjectSnapshot(
                                        "SZ300750",
                                        "宁德时代",
                                        "电池",
                                        "0.300750",
                                        MarketSyncSpec.A_SHARE_STOCK)));
        when(store.backfillIndustryIfAbsent(anyString(), anyString())).thenReturn(1);

        // Act：不抛（通道 A 失败被吞，通道 B 补位）
        IndustryMemberBackfillService.BackfillReport report = service.backfillIfBelowFloor();

        // Assert
        assertThat(report.datacenterUpdated()).isZero();
        assertThat(report.clistUpdated()).isEqualTo(2);
        verify(store).backfillIndustryIfAbsent("SH600519", "白酒Ⅱ");
        verify(store).backfillIndustryIfAbsent("SZ300750", "电池");
    }

    @Test
    void bothChannelsFail_degradesSilently_reportStillProduced() {
        // Arrange：双通道全失败（弹性裁剪：整段失败降级继续——漏斗不因回填停摆）
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenThrow(new IllegalStateException("respredict 拉取失败"));
        when(clistSource.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("clist total 完整性校验失败"));

        // Act + Assert：不抛、attempted=true、计数全 0
        IndustryMemberBackfillService.BackfillReport report = service.backfillIfBelowFloor();
        assertThat(report.attempted()).isTrue();
        assertThat(report.datacenterUpdated()).isZero();
        assertThat(report.clistUpdated()).isZero();
        assertThat(report.detail()).contains("coverage=0.000").contains("dc=0").contains("clist=0");
    }

    @Test
    void clistSkipped_whenDatacenterReachesFloor() {
        // Arrange：datacenter 后覆盖率已达阈值（afterDatacenter 回读 = 4699/5221 ≈ 0.9）→ clist 不再尝试
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenReturn(List.of(new IndustryBoardRow("600519", "白酒Ⅱ")));
        when(store.backfillIndustryIfAbsent(anyString(), anyString())).thenReturn(1);
        when(store.countActiveASharesWithIndustry())
                .thenReturn(0L) // 预检
                .thenReturn(4699L) // datacenter 后（≥ floor）
                .thenReturn(4699L); // 终读
        when(store.countActiveAShares()).thenReturn(5221L);

        // Act
        service.backfillIfBelowFloor();

        // Assert：机会通道零尝试（确定性通道已达标——重外呼零收益）
        verify(clistSource, never()).fetchAll(any(MarketSyncSpec.class));
    }

    @Test
    void idempotentRerun_secondRoundZeroWrites() {
        // Arrange：幂等红线（§6 测试要点：回填幂等两连跑——只补 NULL，二轮零 UPDATE）
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenReturn(List.of(new IndustryBoardRow("600519", "白酒Ⅱ")));
        when(store.backfillIndustryIfAbsent(anyString(), anyString()))
                .thenReturn(1) // 一轮：NULL 行补填成功
                .thenReturn(0); // 二轮：行已非空 → WHERE industry IS NULL 零命中

        // Act：两连跑
        IndustryMemberBackfillService.BackfillReport first = service.backfillIfBelowFloor();
        IndustryMemberBackfillService.BackfillReport second = service.backfillIfBelowFloor();

        // Assert：一轮写入 1，二轮幂等 0（store 层 SQL 守卫的编排面断言）
        assertThat(first.datacenterUpdated()).isEqualTo(1);
        assertThat(second.datacenterUpdated()).isZero();
    }

    @Test
    void blankIndustryRows_skipped_noWrite() {
        coverage(0L, 5221L);
        when(datacenterBoards.fetchIndustryBoards())
                .thenReturn(
                        List.of(
                                new IndustryBoardRow("600519", ""),
                                new IndustryBoardRow("000001", null),
                                new IndustryBoardRow(null, "银行Ⅱ")));

        // Act
        IndustryMemberBackfillService.BackfillReport report = service.backfillIfBelowFloor();

        // Assert：空板块行不产生写入（null code → prefixedCodeOf 原样透传，store 零命中）
        assertThat(report.datacenterUpdated()).isZero();
        verify(store, never()).backfillIndustryIfAbsent(anyString(), anyString());
    }

    @Test
    void prefixedCodeOf_marketPrefixMatrix() {
        assertThat(IndustryMemberBackfillService.prefixedCodeOf("600519")).isEqualTo("SH600519");
        assertThat(IndustryMemberBackfillService.prefixedCodeOf("688981")).isEqualTo("SH688981");
        assertThat(IndustryMemberBackfillService.prefixedCodeOf("000001")).isEqualTo("SZ000001");
        assertThat(IndustryMemberBackfillService.prefixedCodeOf("300024")).isEqualTo("SZ300024");
        assertThat(IndustryMemberBackfillService.prefixedCodeOf("920001")).isEqualTo("BJ920001");
        assertThat(IndustryMemberBackfillService.prefixedCodeOf(null)).isNull();
    }
}
