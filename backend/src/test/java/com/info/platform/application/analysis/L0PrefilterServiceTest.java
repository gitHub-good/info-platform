package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AiExclusion;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0PrefilterService 单测（T120，方案 §4.2；T125 增 aiExclusion=ALL 排除面）：两段判定建行（noise 优先 → 近重复）、批内 PASS
 * 主条入池、空候选静默、 行字段完整性 （l0_result/near_dup_of/l0_detail）。mock 仓储 + 缺省参数
 * PipelineSettings（RuntimeConfigService 空读 → 代码缺省）。 AAA 结构。
 */
class L0PrefilterServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private NewsAnalysisRepository repository;
    private AiExclusionResolver exclusionResolver;
    private L0PrefilterService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        exclusionResolver = mock(AiExclusionResolver.class);
        when(exclusionResolver.excludedSourceIds(any())).thenReturn(List.of());
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new L0PrefilterService(
                        repository,
                        settings,
                        exclusionResolver,
                        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static NewsAnalysisRepository.NewsCandidate candidate(
            long id, String title, String summary) {
        return candidate(id, null, title, summary);
    }

    private static NewsAnalysisRepository.NewsCandidate candidate(
            long id, String externalId, String title, String summary) {
        return new NewsAnalysisRepository.NewsCandidate(
                id,
                1L,
                externalId,
                title,
                summary,
                null,
                NOW.minusSeconds(300),
                NOW.minusSeconds(120));
    }

    @Test
    void run_emptyCandidates_silentZeroReport() {
        // Arrange
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt())).thenReturn(List.of());

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert：空批静默（无池查询、无落库）
        assertThat(report.pass()).isZero();
        assertThat(report.noise()).isZero();
        assertThat(report.nearDup()).isZero();
        verify(repository, never()).insertIgnoreBatch(anyList());
    }

    @Test
    void run_mixedBatch_buildsThreeStateRows() {
        // Arrange：noise 条 + 批内同稿双条 + 独立条
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(
                        List.of(
                                candidate(1, "广告：开户礼佣金万一", "摘要"),
                                candidate(2, "美元兑日元USD/JPY日内下跌1.00%", "摘要"),
                                candidate(3, "【快讯】美元兑日元USD/JPY日内下跌1.00%", "摘要"),
                                candidate(4, "贵州茅台发布半年度业绩预告", "摘要")));
        when(repository.findPassPoolSince(anyString(), anyInt())).thenReturn(List.of());

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert
        assertThat(report.pass()).isEqualTo(2);
        assertThat(report.noise()).isEqualTo(1);
        assertThat(report.nearDup()).isEqualTo(1);
        assertThat(report.detail()).isEqualTo("l0=pass:2; noise:1; near_dup:1");

        ArgumentCaptor<List<NewsAnalysis>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertIgnoreBatch(captor.capture());
        Map<Long, NewsAnalysis> byNewsId = new java.util.HashMap<>();
        captor.getValue().forEach(row -> byNewsId.put(row.getNewsId(), row));

        assertThat(byNewsId.get(1L).getL0Result()).isEqualTo(L0Result.NOISE);
        assertThat(byNewsId.get(1L).getL0Detail()).startsWith("noise:keyword:");
        assertThat(byNewsId.get(3L).getL0Result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(byNewsId.get(3L).getNearDupOf()).isEqualTo(2L);
        assertThat(byNewsId.get(3L).getL0Detail()).startsWith("hamming=").contains(";lev=");
        assertThat(byNewsId.get(2L).getL0Result()).isEqualTo(L0Result.PASS);
        assertThat(byNewsId.get(2L).getNearDupOf()).isNull();
    }

    @Test
    void run_noiseTakesPriorityOverNearDup() {
        // Arrange：noise 条同时与池内 PASS 条同稿——先判 noise，不参与主条竞争
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(candidate(20, "美元兑日元USD/JPY日内下跌1.00%（广告合作）", "摘要")));
        when(repository.findPassPoolSince(anyString(), anyInt()))
                .thenReturn(List.of(candidate(10, "美元兑日元USD/JPY日内下跌1.00%", "摘要")));

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert
        assertThat(report.noise()).isEqualTo(1);
        assertThat(report.nearDup()).isZero();
        ArgumentCaptor<List<NewsAnalysis>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertIgnoreBatch(captor.capture());
        assertThat(captor.getValue().get(0).getL0Result()).isEqualTo(L0Result.NOISE);
        assertThat(captor.getValue().get(0).getNearDupOf()).isNull();
    }

    @Test
    void run_poolEntryAbsorbsIncomingDuplicate() {
        // Arrange：24h 池内已有主条，新批同稿 → NEAR_DUP 引用池内主条
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(candidate(30, "欧洲央行维持三大关键利率不变", "摘要")));
        when(repository.findPassPoolSince(anyString(), anyInt()))
                .thenReturn(List.of(candidate(12, "欧洲央行维持三大关键利率不变", "摘要")));

        // Act
        service.run();

        // Assert
        ArgumentCaptor<List<NewsAnalysis>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertIgnoreBatch(captor.capture());
        NewsAnalysis row = captor.getValue().get(0);
        assertThat(row.getL0Result()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(row.getNearDupOf()).isEqualTo(12L);
    }

    @Test
    void run_neDupRowRequiresMainRef_entityGuard() {
        // 实体守卫：NEAR_DUP 无主条引用直接拒绝（白名单语义在领域层把守）
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> NewsAnalysis.newForL0(1L, L0Result.NEAR_DUP, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> NewsAnalysis.newForL0(1L, L0Result.PASS, 9L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- T130 · BUG-03 序列豁免（M16 方案 §4.2：月度序列条目永不标 NEAR_DUP，恢复事件源） ----

    @Test
    void run_monthlySeriesCandidates_allPassNoNearDup() {
        // 修前红：CPI 月度序列两条仅月份数字不同——当前实现后到者 NEAR_DUP；豁免后全 PASS
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(
                        List.of(
                                candidate(41, "CPI：2026年08月份 同比 0.8%", "宏观"),
                                candidate(42, "CPI：2026年09月份 同比 0.7%", "宏观")));
        when(repository.findPassPoolSince(anyString(), anyInt())).thenReturn(List.of());

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert
        assertThat(report.pass()).isEqualTo(2);
        assertThat(report.nearDup()).as("月度序列条目豁免近重复（BUG-03）").isZero();
        ArgumentCaptor<List<NewsAnalysis>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertIgnoreBatch(captor.capture());
        for (NewsAnalysis row : captor.getValue()) {
            assertThat(row.getL0Result()).isEqualTo(L0Result.PASS);
            assertThat(row.getNearDupOf()).isNull();
        }
    }

    @Test
    void run_nonSeriesDuplicate_stillNearDup_regression() {
        // 回归红线：普通近重复照常合并（豁免不外溢）
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(
                        List.of(
                                candidate(51, "美元兑日元USD/JPY日内下跌1.00%", "摘要"),
                                candidate(52, "【快讯】美元兑日元USD/JPY日内下跌1.00%", "摘要")));
        when(repository.findPassPoolSince(anyString(), anyInt())).thenReturn(List.of());

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert
        assertThat(report.nearDup()).isEqualTo(1);
    }

    @Test
    void run_exemptCandidates_carrySeqExemptDetailAndCount() {
        // Arrange：# 标记条 + 月度标题条 + 普通条——豁免留痕 token 落 l0_detail，tick 明细计数 seq_exempt=2
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt()))
                .thenReturn(
                        List.of(
                                candidate(61, "RPT_ECONOMY_CPI#2026-08-01", "居民消费价格指数发布", "宏观"),
                                candidate(62, null, "PPI：2026年08月份 同比下降 1.2%", "宏观"),
                                candidate(63, null, "贵州茅台发布半年度业绩预告", "公司")));
        when(repository.findPassPoolSince(anyString(), anyInt())).thenReturn(List.of());

        // Act
        L0PrefilterService.L0Report report = service.run();

        // Assert
        assertThat(report.pass()).isEqualTo(3);
        assertThat(report.seqExempt()).isEqualTo(2);
        assertThat(report.detail()).isEqualTo("l0=pass:3; noise:0; near_dup:0; seq_exempt:2");
        ArgumentCaptor<List<NewsAnalysis>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertIgnoreBatch(captor.capture());
        Map<Long, NewsAnalysis> byNewsId = new java.util.HashMap<>();
        captor.getValue().forEach(row -> byNewsId.put(row.getNewsId(), row));
        assertThat(byNewsId.get(61L).getL0Detail()).isEqualTo("seq-exempt:#");
        assertThat(byNewsId.get(62L).getL0Detail()).isEqualTo("seq-exempt:monthly");
        assertThat(byNewsId.get(63L).getL0Detail()).isNull();
    }

    // ---- T125：aiExclusion=ALL 源条目不建 analysis 行（REQ 拍板五-1 两分支之 ALL） ----

    @Test
    void run_allExcludedSources_passedToRepositoryQuery() {
        // Arrange
        when(repository.findUnanalyzed(anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(exclusionResolver.excludedSourceIds(AiExclusion.ALL)).thenReturn(List.of(21L, 22L));

        // Act
        service.run();

        // Assert：排除清单下传 L0 建行候选查询（aiExclusion=ALL 不建 analysis 行）
        verify(repository).findUnanalyzed(anyString(), eq(List.of(21L, 22L)), anyInt());
    }
}
