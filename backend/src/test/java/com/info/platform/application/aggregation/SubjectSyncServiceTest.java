package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SubjectSyncService / SubjectSyncWriter 单测（T51/T52/T54，Mockito 隔离——不触 DB、不触 HTTP）：市场级独立与汇总异常 /
 * 拉取失败零写入 / T52 阈值停用挂点 / T54 指数桶开关与 Should 失败语义 / diff 四分支分类与批量分批（§4.3 流程 A/B、§6 引擎 diff 要点； streak
 * 语义的库级断言在集成测试）。
 */
class SubjectSyncServiceTest {

    // ---- SubjectSyncService：跨市场独立 / 汇总异常（§4.5） ----

    @Test
    void syncAll_allMarketsSuccess_returnsResultsInBucketOrder() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK))
                .thenReturn(List.of(snapshot("00700", "腾讯控股")));
        MarketSyncResult aShare =
                new MarketSyncResult(MarketSyncSpec.A_SHARE_STOCK, 1, 0, 0, 0, 0, 1, 5);
        MarketSyncResult hk = new MarketSyncResult(MarketSyncSpec.HK_STOCK, 1, 0, 0, 0, 0, 1, 5);
        when(writer.writeBucket(any(), anyList())).thenReturn(aShare).thenReturn(hk);

        List<MarketSyncResult> results = new SubjectSyncService(source, writer, false).syncAll();

        assertThat(results).containsExactly(aShare, hk);
    }

    @Test
    void syncAll_hkFails_aShareStillApplied_andThrowsSummaryWithCounts() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK))
                .thenThrow(new IllegalStateException("clist 第 12 页拉取失败（重试耗尽）bucket=HK_STOCK"));
        MarketSyncResult aShare =
                new MarketSyncResult(MarketSyncSpec.A_SHARE_STOCK, 5561, 0, 0, 0, 0, 5561, 95000);
        when(writer.writeBucket(eq(MarketSyncSpec.A_SHARE_STOCK), anyList())).thenReturn(aShare);

        SubjectSyncService service = new SubjectSyncService(source, writer, false);
        assertThatThrownBy(service::syncAll)
                .isInstanceOf(SubjectSyncException.class)
                .hasMessageContaining("标的池同步部分失败")
                .hasMessageContaining("HK_STOCK FAILED")
                .hasMessageContaining("第 12 页");

        // A 股已生效（写库被调用）；港股零写入（writer 未收到 HK 桶）
        verify(writer).writeBucket(eq(MarketSyncSpec.A_SHARE_STOCK), anyList());
        verify(writer, never()).writeBucket(eq(MarketSyncSpec.HK_STOCK), anyList());
    }

    @Test
    void syncAll_allMarketsFail_failuresCarryEveryBucket() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(any())).thenThrow(new IllegalStateException("放弃"));

        SubjectSyncException exception = catchSyncException(source, writer);

        // §4.5：跨市场独立——每个失败市场各占一条摘要，成功计数列表为空
        assertThat(exception.getFailures()).hasSize(2);
        assertThat(exception.getFailures().get(0)).contains("A_SHARE_STOCK FAILED");
        assertThat(exception.getFailures().get(1)).contains("HK_STOCK FAILED");
        assertThat(exception.getResults()).isEmpty();
    }

    @Test
    void syncMarket_fetchFails_writerNotCalled_zeroWrite() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(MarketSyncSpec.HK_STOCK))
                .thenThrow(new IllegalStateException("total 校验失败"));
        SubjectSyncService service = new SubjectSyncService(source, writer, false);

        assertThatThrownBy(() -> service.syncMarket(MarketSyncSpec.HK_STOCK))
                .isInstanceOf(IllegalStateException.class);
        verify(writer, never()).writeBucket(any(), anyList());
    }

    // ---- SubjectSyncService：T54 指数桶开关与 Should 失败语义（§4.3） ----

    @Test
    void syncedBuckets_indexSwitch_appendsOrOmitsIndexBucket() {
        SubjectSyncService indexOn =
                new SubjectSyncService(
                        mock(SubjectListSource.class), mock(SubjectSyncWriter.class), true);
        SubjectSyncService indexOff =
                new SubjectSyncService(
                        mock(SubjectListSource.class), mock(SubjectSyncWriter.class), false);

        // 开（默认）：A 股 → 港股 → 指数（顺序即执行顺序）；关（Should 可关）：仅两股票桶
        assertThat(indexOn.syncedBuckets())
                .containsExactly(
                        MarketSyncSpec.A_SHARE_STOCK,
                        MarketSyncSpec.HK_STOCK,
                        MarketSyncSpec.CN_INDEX);
        assertThat(indexOff.syncedBuckets())
                .containsExactly(MarketSyncSpec.A_SHARE_STOCK, MarketSyncSpec.HK_STOCK);
    }

    @Test
    void syncAll_indexFails_warnOnly_stockResultsReturned() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "贵州茅台")));
        when(source.fetchAll(MarketSyncSpec.HK_STOCK))
                .thenReturn(List.of(snapshot("00700", "腾讯控股")));
        when(source.fetchAll(MarketSyncSpec.CN_INDEX))
                .thenThrow(new IllegalStateException("clist total 完整性校验失败 bucket=CN_INDEX"));
        MarketSyncResult aShare =
                new MarketSyncResult(MarketSyncSpec.A_SHARE_STOCK, 1, 0, 0, 0, 0, 1, 5);
        MarketSyncResult hk = new MarketSyncResult(MarketSyncSpec.HK_STOCK, 1, 0, 0, 0, 0, 1, 5);
        when(writer.writeBucket(any(), anyList())).thenReturn(aShare).thenReturn(hk);
        SubjectSyncService service = new SubjectSyncService(source, writer, true);

        // Should 语义：指数桶失败仅 WARN——不抛汇总异常，股票结果照常返回
        List<MarketSyncResult> results = service.syncAll();

        assertThat(results).containsExactly(aShare, hk);
        verify(writer, never()).writeBucket(eq(MarketSyncSpec.CN_INDEX), anyList());
    }

    @Test
    void syncAll_indexSucceeds_includedInResults() {
        SubjectListSource source = mock(SubjectListSource.class);
        SubjectSyncWriter writer = mock(SubjectSyncWriter.class);
        when(source.fetchAll(any())).thenReturn(List.of());
        MarketSyncResult aShare =
                new MarketSyncResult(MarketSyncSpec.A_SHARE_STOCK, 0, 0, 0, 0, 0, 0, 1);
        MarketSyncResult hk = new MarketSyncResult(MarketSyncSpec.HK_STOCK, 0, 0, 0, 0, 0, 0, 1);
        MarketSyncResult index = new MarketSyncResult(MarketSyncSpec.CN_INDEX, 0, 0, 4, 0, 0, 4, 1);
        when(writer.writeBucket(any(), anyList()))
                .thenReturn(aShare)
                .thenReturn(hk)
                .thenReturn(index);

        List<MarketSyncResult> results = new SubjectSyncService(source, writer, true).syncAll();

        assertThat(results).containsExactly(aShare, hk, index);
    }

    // ---- SubjectSyncWriter：T52 阈值停用（§4.3 流程 B 缺失分支） ----

    @Test
    void writeBucket_missingAtThreshold_deactivates_countsRealFlipsOnly() {
        SubjectRepository repository = mock(SubjectRepository.class);
        // 达阈值行（旧 streak 2 → +1 = 3 ≥ 3）与未达行（0 → 1）与已停用缺失行（不参与）
        Subject atThreshold =
                reconstruct(
                        "SH600900",
                        "长城证券",
                        "证券",
                        SubjectStatus.ENABLED,
                        2,
                        codes("1.600900", "600900.SH"));
        Subject belowThreshold =
                reconstruct(
                        "SZ002900",
                        "消费观察",
                        null,
                        SubjectStatus.ENABLED,
                        0,
                        codes("0.002900", "002900.SZ"));
        Subject disabledMissing =
                reconstruct(
                        "SH601899",
                        "中远海特",
                        "交运",
                        SubjectStatus.DISABLED,
                        5,
                        codes("1.601899", "601899.SH"));
        when(repository.loadBucket(Market.A_SHARE, SubjectType.STOCK))
                .thenReturn(List.of(atThreshold, belowThreshold, disabledMissing));
        when(repository.deactivateIfMissingReached("SH600900", 3)).thenReturn(1);

        MarketSyncResult result =
                new SubjectSyncWriter(repository, 500, 3)
                        .writeBucket(MarketSyncSpec.A_SHARE_STOCK, List.of());

        // 停用数 = 端口真实翻转行数；未达阈值行不调停用端口；已停用缺失行既不计数也不停用
        assertThat(result.missing()).isEqualTo(2);
        assertThat(result.deactivated()).isEqualTo(1);
        verify(repository).incrementMissingStreak("SH600900");
        verify(repository).incrementMissingStreak("SZ002900");
        verify(repository).deactivateIfMissingReached("SH600900", 3);
        verify(repository, never()).deactivateIfMissingReached(eq("SZ002900"), anyInt());
        verify(repository, never()).incrementMissingStreak("SH601899");
        verify(repository, never()).deactivateIfMissingReached(eq("SH601899"), anyInt());
    }

    @Test
    void writeBucket_missingAtThreshold_thresholdConfigurable() {
        SubjectRepository repository = mock(SubjectRepository.class);
        Subject missing =
                reconstruct(
                        "SH600901",
                        "长江证券",
                        "证券",
                        SubjectStatus.ENABLED,
                        1,
                        codes("1.600901", "600901.SH"));
        when(repository.loadBucket(Market.A_SHARE, SubjectType.STOCK)).thenReturn(List.of(missing));

        // 阈值 2：旧 streak 1 → +1 = 2 ≥ 2 即停用（参数化生效）
        new SubjectSyncWriter(repository, 500, 2)
                .writeBucket(MarketSyncSpec.A_SHARE_STOCK, List.of());

        verify(repository).deactivateIfMissingReached("SH600901", 2);
    }

    // ---- SubjectSyncWriter：diff 四分支与批量分批（§4.3 流程 B） ----

    @Test
    void writeBucket_newRows_only_insertsInBatchesOfBatchSize() {
        SubjectRepository repository = mock(SubjectRepository.class);
        when(repository.loadBucket(Market.A_SHARE, SubjectType.STOCK)).thenReturn(List.of());
        // batchSize=2、5 只新标的 → 3 批（2/2/1）；inserted = 各批实际插入之和
        when(repository.insertIgnoreBatch(anyList())).thenReturn(2).thenReturn(2).thenReturn(1);

        MarketSyncResult result =
                new SubjectSyncWriter(repository, 2, 3)
                        .writeBucket(
                                MarketSyncSpec.A_SHARE_STOCK,
                                List.of(
                                        snapshot("688001", "华兴源创"),
                                        snapshot("688002", "睿创微纳"),
                                        snapshot("688003", "澜起科技"),
                                        snapshot("688004", "心脉医疗"),
                                        snapshot("688005", "容百科技")));

        assertThat(result.inserted()).isEqualTo(5);
        assertThat(result.updated()).isZero();
        assertThat(result.missing()).isZero();
        assertThat(result.total()).isEqualTo(5);
        // 批量边界：5 行分 3 批（2/2/1）
        ArgumentCaptor<List<Subject>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(3)).insertIgnoreBatch(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
        // 新增行经 Subject 构建带桶的 market/type（INSERT 语义）
        assertThat(captor.getAllValues().get(0).get(0).getSubjectCode().value())
                .isEqualTo("SH688001");
        assertThat(captor.getAllValues().get(0).get(0).getMarket()).isEqualTo(Market.A_SHARE);
    }

    @Test
    void writeBucket_appearedRows_updatesOnlyChangedAndClearsStreak() {
        SubjectRepository repository = mock(SubjectRepository.class);
        Subject unchanged =
                reconstruct(
                        "SH600519",
                        "贵州茅台",
                        "白酒",
                        SubjectStatus.ENABLED,
                        0,
                        codes("1.600519", "600519.SH"));
        Subject renamed =
                reconstruct(
                        "SH600036",
                        "招商银行旧名",
                        "银行",
                        SubjectStatus.ENABLED,
                        0,
                        codes("1.600036", "600036.SH"));
        Subject industryChanged =
                reconstruct(
                        "SZ300750",
                        "宁德时代",
                        null,
                        SubjectStatus.ENABLED,
                        0,
                        codes("0.300750", "300750.SZ"));
        Subject streakOnly =
                reconstruct(
                        "SH601318",
                        "中国平安",
                        "保险",
                        SubjectStatus.ENABLED,
                        2,
                        codes("1.601318", "601318.SH"));
        Subject missingEnabled =
                reconstruct(
                        "SZ000858",
                        "五粮液",
                        "白酒",
                        SubjectStatus.ENABLED,
                        0,
                        codes("0.000858", "000858.SZ"));
        Subject missingDisabled =
                reconstruct(
                        "SH601857",
                        "中国石油",
                        "石油石化",
                        SubjectStatus.DISABLED,
                        0,
                        codes("1.601857", "601857.SH"));
        when(repository.loadBucket(Market.A_SHARE, SubjectType.STOCK))
                .thenReturn(
                        List.of(
                                unchanged,
                                renamed,
                                industryChanged,
                                streakOnly,
                                missingEnabled,
                                missingDisabled));

        MarketSyncResult result =
                new SubjectSyncWriter(repository, 500, 3)
                        .writeBucket(
                                MarketSyncSpec.A_SHARE_STOCK,
                                List.of(
                                        snapshot("600519", "贵州茅台", "白酒"),
                                        snapshot("600036", "招商银行", "银行"),
                                        snapshot("300750", "宁德时代", "动力电池"),
                                        snapshot("601318", "中国平安", "保险")));

        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isEqualTo(2); // 改名 + 行业变化
        assertThat(result.unchanged()).isEqualTo(2); // 完全一致 + 仅 streak 清零（字段未变）
        assertThat(result.missing()).isEqualTo(1); // 仅启用缺失行；已停用不计数
        assertThat(result.total()).isEqualTo(4);

        // 更新只写名称/行业/取数键（端口契约保证不碰 status/streak）
        verify(repository)
                .updateSnapshot(
                        anyString(),
                        anyString(),
                        anyString(),
                        codesArgThat("1.600036", "600036.SH"));
        verify(repository)
                .updateSnapshot(
                        anyString(),
                        anyString(),
                        anyString(),
                        codesArgThat("0.300750", "300750.SZ"));
        // streak>0 的出现行清零（回归）；字段未变行不清库、不更新
        verify(repository).clearMissingStreak("SH601318");
        verify(repository, never()).clearMissingStreak("SH600519");
        // 快照更新仅命中变化的两行（完全一致行与仅 streak 清零行不写）
        verify(repository, times(2)).updateSnapshot(anyString(), anyString(), anyString(), any());
        verify(repository, times(1)).incrementMissingStreak(anyString());
        verify(repository).incrementMissingStreak("SZ000858");
        // 永不新增（无新代码）
        verify(repository, never()).insertIgnoreBatch(anyList());
    }

    @Test
    void writeBucket_existingExtraCodeKeys_preservedOnUpdate() {
        SubjectRepository repository = mock(SubjectRepository.class);
        // 手工行带 akshare 额外键：同步补 eastmoney/tushare 时合并不丢（ADR-0029：external_codes 增量合入）
        Subject manualRow =
                reconstruct(
                        "SH601988",
                        "中国银行",
                        "银行",
                        SubjectStatus.ENABLED,
                        0,
                        Map.of("akshare", "sh601988"));
        when(repository.loadBucket(Market.A_SHARE, SubjectType.STOCK))
                .thenReturn(List.of(manualRow));

        new SubjectSyncWriter(repository, 500, 3)
                .writeBucket(
                        MarketSyncSpec.A_SHARE_STOCK, List.of(snapshot("601988", "中国银行", "银行")));

        ArgumentCaptor<Map<String, String>> codesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository)
                .updateSnapshot(anyString(), anyString(), anyString(), codesCaptor.capture());
        assertThat(codesCaptor.getValue())
                .containsEntry("akshare", "sh601988")
                .containsEntry("eastmoney", "1.601988")
                .containsEntry("tushare", "601988.SH");
    }

    // ---- helpers ----

    private static SubjectSnapshot snapshot(String code, String name) {
        return snapshot(code, name, null);
    }

    /** 单测代码约定：5 位为港股（f13=116），6 开头沪（1），其余深（0）。 */
    private static SubjectSnapshot snapshot(String code, String name, String industry) {
        int f13 = code.length() == 5 ? 116 : code.startsWith("6") ? 1 : 0;
        return new SubjectSnapshot(
                MarketSyncSpec.codePrefixOf(f13) + code,
                name,
                industry,
                f13 + "." + code,
                f13 == 116 ? MarketSyncSpec.HK_STOCK : MarketSyncSpec.A_SHARE_STOCK);
    }

    private static Subject reconstruct(
            String code,
            String name,
            String industry,
            SubjectStatus status,
            int missingStreak,
            Map<String, String> externalCodes) {
        return Subject.reconstruct(
                null,
                SubjectCode.of(code),
                code.startsWith("SZ")
                        ? Market.A_SHARE
                        : code.startsWith("HK") ? Market.HK : Market.A_SHARE,
                SubjectType.STOCK,
                name,
                externalCodes,
                industry,
                status,
                0,
                Instant.EPOCH,
                Instant.EPOCH,
                missingStreak);
    }

    private static Map<String, String> codes(String eastmoney, String tushare) {
        return Map.of("eastmoney", eastmoney, "tushare", tushare);
    }

    private static Map<String, String> codesArgThat(String eastmoney, String tushare) {
        return org.mockito.ArgumentMatchers.argThat(
                map ->
                        map != null
                                && eastmoney.equals(map.get("eastmoney"))
                                && tushare.equals(map.get("tushare")));
    }

    private static SubjectSyncException catchSyncException(
            SubjectListSource source, SubjectSyncWriter writer) {
        try {
            new SubjectSyncService(source, writer, false).syncAll();
            throw new AssertionError("应抛 SubjectSyncException");
        } catch (SubjectSyncException e) {
            return e;
        }
    }
}
