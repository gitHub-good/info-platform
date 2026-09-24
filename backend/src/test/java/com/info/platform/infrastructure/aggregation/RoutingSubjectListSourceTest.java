package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RoutingSubjectListSource 单测（M7 备选源切换，ADR-0030）：Mockito 隔离两实现——auto 东财成功不碰新浪 / 东财失败自动 fallback
 * 新浪重拉整桶 / 双失败抛桶失败（东财原因挂 suppressed）/ 强制单源模式（排障）/ 非 A 股桶恒东财（新浪无港股节点）/ 非法配置值启动即失败（fail-fast）。
 */
class RoutingSubjectListSourceTest {

    private static final String EAST_FAILURE = "clist 第 1 页拉取失败（重试耗尽）bucket=A_SHARE_STOCK";

    @Test
    void auto_eastMoneySucceeds_sinaNeverCalled() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        List<SubjectSnapshot> fromEast = List.of(snapshot("600519", "1.600519"));
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(fromEast);

        List<SubjectSnapshot> result =
                new RoutingSubjectListSource(eastMoney, sina, "auto")
                        .fetchAll(MarketSyncSpec.A_SHARE_STOCK);

        assertThat(result).isSameAs(fromEast);
        verify(sina, never()).fetchAll(any());
    }

    @Test
    void auto_eastMoneyFails_fallsBackToSina() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException(EAST_FAILURE));
        List<SubjectSnapshot> fromSina = List.of(snapshot("600519", "1.600519"));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(fromSina);

        List<SubjectSnapshot> result =
                new RoutingSubjectListSource(eastMoney, sina, "auto")
                        .fetchAll(MarketSyncSpec.A_SHARE_STOCK);

        // 降级语义：整桶重拉（非增量续传），返回新浪全量
        assertThat(result).isSameAs(fromSina);
        verify(eastMoney, times(1)).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        verify(sina, times(1)).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
    }

    @Test
    void auto_bothFail_throwsSinaFailureWithEastFailureSuppressed() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException(EAST_FAILURE));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("新浪列表首页为空（防假空）bucket=A_SHARE_STOCK"));

        // 双失败 = 该桶本轮失败（新浪异常上抛，东财原因挂 suppressed 留诊断链）
        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                new RoutingSubjectListSource(eastMoney, sina, "auto")
                                        .fetchAll(MarketSyncSpec.A_SHARE_STOCK));
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("首页为空");
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0])
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(EAST_FAILURE);
    }

    @Test
    void forcedEastMoney_failurePropagates_noSinaFallback() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException(EAST_FAILURE));

        // 排障模式：强制单源，失败即桶失败（验证降级逻辑确因配置关闭）
        assertThatThrownBy(
                        () ->
                                new RoutingSubjectListSource(eastMoney, sina, "eastmoney")
                                        .fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .hasMessageContaining(EAST_FAILURE);
        verify(sina, never()).fetchAll(any());
    }

    @Test
    void forcedSina_eastMoneyNeverCalled_modeCaseInsensitive() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        List<SubjectSnapshot> fromSina = List.of(snapshot("600519", "1.600519"));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(fromSina);

        // 配置值大小写不敏感（"Sina" 归一化）
        List<SubjectSnapshot> result =
                new RoutingSubjectListSource(eastMoney, sina, "Sina")
                        .fetchAll(MarketSyncSpec.A_SHARE_STOCK);

        assertThat(result).isSameAs(fromSina);
        verify(eastMoney, never()).fetchAll(any());
    }

    @Test
    void nonAShareBucket_alwaysEastMoney_evenForcedSina() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        List<SubjectSnapshot> fromEast = List.of(snapshot("00700", "116.00700"));
        when(eastMoney.fetchAll(MarketSyncSpec.HK_STOCK)).thenReturn(fromEast);

        // 港股/指数桶恒东财（新浪无港股节点，ADR-0030）——即使强制 sina 也只作用于 A 股桶
        List<SubjectSnapshot> result =
                new RoutingSubjectListSource(eastMoney, sina, "sina")
                        .fetchAll(MarketSyncSpec.HK_STOCK);

        assertThat(result).isSameAs(fromEast);
        verify(sina, never()).fetchAll(any());
    }

    @Test
    void invalidMode_failsFastAtConstruction() {
        // 配置笔误启动即失败（fail-fast），提示合法取值
        assertThatThrownBy(
                        () ->
                                new RoutingSubjectListSource(
                                        mock(EastMoneyListClient.class),
                                        mock(SinaSubjectListClient.class),
                                        "sian"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto")
                .hasMessageContaining("eastmoney")
                .hasMessageContaining("sina");
    }

    @Test
    void blankMode_defaultsToAuto() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException(EAST_FAILURE));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("600519", "1.600519")));

        // 空值容错回落 auto（语义同 @Value 缺省）
        assertThat(
                        new RoutingSubjectListSource(eastMoney, sina, " ")
                                .fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .hasSize(1);
        verify(sina, times(1)).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
    }

    // ---- helpers ----

    private static SubjectSnapshot snapshot(String code, String secid) {
        return new SubjectSnapshot(
                secid.startsWith("116.")
                        ? "HK" + code
                        : ("0".equals(secid.substring(0, 1)) ? "SZ" : "SH") + code,
                "样本标的",
                null,
                secid,
                secid.startsWith("116.") ? MarketSyncSpec.HK_STOCK : MarketSyncSpec.A_SHARE_STOCK);
    }
}
