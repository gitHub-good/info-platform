package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.jobrun.RunningJobIndicator;
import com.info.platform.application.markettop.IncrementalTopService;
import com.info.platform.application.valuation.FactorSnapshotService.IncrementalReport;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository.PoolRow;
import com.info.platform.domain.valuation.IncrementalReevalRepository;
import com.info.platform.domain.valuation.IncrementalReevalRepository.PendingLink;
import com.info.platform.domain.valuation.IncrementalReevalRepository.ReevalEvent;
import com.info.platform.domain.valuation.IncrementalReevalRepository.SubjectScore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * IncrementalReevalService tick 状态机单测（M22 T190，方案 §4.3.1 伪码逐段）：重 Job 让路（互斥层②）/
 * 扫描判重（UNIQUE(event_id) 一轮一消费）/ 合并去抖（同窗多事件按标的并集一轮重算）/ 行业投影同源（路 C 成员边）/ 留痕四要素（标的集 + 前后分）/ 未过阈
 * NO_LINK / 过阈挂起 RECOMPUTED（联动段 T191 接线）/ 挂起轮不重算 / 异常 FAILED 不上抛。仓储与编排协作全 mock（纯状态机面）；零漂移归
 * FactorSnapshotServiceTest。
 */
class IncrementalReevalServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    /** 定长时钟（Asia/Shanghai 当日 = TODAY：UTC 09-28 02:00 = 上海 10:00）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneOffset.UTC);

    private static final Instant NOW = Instant.parse("2026-09-28T02:00:00Z");

    private IncrementalReevalRepository logRepository;

    private FactorSnapshotRepository snapshotRepository;

    private FactorSnapshotService factorSnapshotService;

    private MarketTopRepository marketTopRepository;

    private IncrementalTopService incrementalTopService;

    private RunningJobIndicator runningIndicator;

    private IncrementalReevalService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        logRepository = mock(IncrementalReevalRepository.class);
        snapshotRepository = mock(FactorSnapshotRepository.class);
        factorSnapshotService = mock(FactorSnapshotService.class);
        marketTopRepository = mock(MarketTopRepository.class);
        incrementalTopService = mock(IncrementalTopService.class);
        runningIndicator = mock(RunningJobIndicator.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        service =
                new IncrementalReevalService(
                        logRepository,
                        snapshotRepository,
                        factorSnapshotService,
                        marketTopRepository,
                        incrementalTopService,
                        new IncrementalReevalSettings(configService, objectMapper),
                        runningIndicator,
                        objectMapper,
                        CLOCK);
        // 公共桩：活跃标的 + 空行业成员 + 当日无留痕
        when(snapshotRepository.findActiveSubjects())
                .thenReturn(
                        List.of(
                                new FactorSnapshotRepository.SubjectRef(1, "SH600519", "贵州茅台"),
                                new FactorSnapshotRepository.SubjectRef(2, "SZ000001", "平安银行"),
                                new FactorSnapshotRepository.SubjectRef(3, "SZ300750", "宁德时代")));
        when(snapshotRepository.findIndustryMembers()).thenReturn(List.of());
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(logRepository.findPendingLink()).thenReturn(List.of());
    }

    private static ReevalEvent event(long eventId, List<String> codes, List<String> industries) {
        return new ReevalEvent(
                eventId,
                NOW.minusSeconds(120).toString(),
                "事件" + eventId,
                Importance.HIGH,
                codes,
                industries);
    }

    private static PoolRow poolRow(long subjectId, String code, String name, double total) {
        return new PoolRow(
                subjectId,
                code,
                name,
                "食品饮料",
                40.0,
                30.0,
                50.0,
                90.0,
                50.0,
                total,
                false,
                "{}",
                "vs-v1:…",
                "2026-09-28");
    }

    private static MarketTopVersion topVersion(double... finals) {
        List<MarketTopRankRow> items = new java.util.ArrayList<>();
        for (int i = 0; i < finals.length; i++) {
            items.add(
                    new MarketTopRankRow(
                            TODAY.toString(),
                            1,
                            i + 1,
                            100L + i,
                            "SH6000" + i,
                            "在榜" + i,
                            50.0,
                            finals[i],
                            90.0,
                            false,
                            "FULL",
                            "LLM",
                            "摘要",
                            "{}",
                            3,
                            "2026-09-28",
                            null,
                            "NEW",
                            "mt-v1:…",
                            "2026-09-28T01:00:00Z"));
        }
        return new MarketTopVersion(
                new MarketTopBatchRow(
                        TODAY.toString(),
                        1,
                        "DAILY",
                        TODAY.toString(),
                        "{}",
                        false,
                        null,
                        "[]",
                        0L,
                        0,
                        null,
                        "mt-v1:…",
                        null,
                        "2026-09-28T01:00:00Z"),
                items);
    }

    private void stubRecompute() {
        when(factorSnapshotService.recomputeIncremental(any(), anyCollection()))
                .thenReturn(new IncrementalReport(1, NOW.toString()));
    }

    private void stubTopAndPool(double affectedTotal) {
        // 在榜 10 名 final 均 60；受影响标的 total（> 60 + 0.5 → 过阈换位）
        when(marketTopRepository.findLatest(TODAY.toString()))
                .thenReturn(Optional.of(topVersion(60, 60, 60, 60, 60, 60, 60, 60, 60, 60)));
        when(snapshotRepository.findPoolRowsByDate(TODAY.toString()))
                .thenReturn(List.of(poolRow(1, "SH600519", "贵州茅台", affectedTotal)));
    }

    // ---- 让路（互斥层②：tick 入口查询，事件不动下轮重扫） ----

    @Test
    void tick_heavyJobRunning_defersWithoutScan() {
        when(runningIndicator.isRunning("FACTOR_SNAPSHOT")).thenReturn(true);

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("defer=heavy_running");
        verify(logRepository, never()).findUnconsumedEvents(any(), any(), any(), anyInt());
        verify(factorSnapshotService, never()).recomputeIncremental(any(), anyCollection());
    }

    @Test
    void tick_marketTopRunning_defersToo() {
        when(runningIndicator.isRunning("MARKET_TOP_JOB")).thenReturn(true);

        assertThat(service.tick().detail()).contains("defer=heavy_running");
    }

    // ---- 空转防抖（无输入零重算零留痕更新） ----

    @Test
    void tick_noEventsNoPending_idleWithoutAnyWork() {
        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("idle");
        verify(factorSnapshotService, never()).recomputeIncremental(any(), anyCollection());
        verify(logRepository, never())
                .markRecomputed(anyCollection(), any(), any(), anyBoolean(), any());
        verify(logRepository, never()).insertScanned(any(), any());
    }

    // ---- 扫描 + 受影响集 + 重算 + 留痕四要素 ----

    @Test
    void tick_highEvent_recomputesAffectedSubjectAndLogsSubjectsJsonWithBeforeAfter() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of(new SubjectScore(1, "SH600519", 55.0)));
        stubRecompute();
        stubTopAndPool(70.0);

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("scan:1");
        // 重算恰一次、目标恰为受影响标的
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(factorSnapshotService)
                .recomputeIncremental(any(LocalDate.class), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L);
        // 留痕四要素：标的集 + 前后分（before=55.0 当日行原值 / after=70.0 重算行）
        ArgumentCaptor<String> subjectsCaptor = ArgumentCaptor.forClass(String.class);
        verify(logRepository)
                .markRecomputed(
                        anyCollection(), subjectsCaptor.capture(), any(), anyBoolean(), any());
        JsonNode subjects = parseJson(subjectsCaptor.getValue());
        assertThat(subjects.isArray()).isTrue();
        assertThat(subjects.get(0).path("id").asLong()).isEqualTo(1L);
        assertThat(subjects.get(0).path("code").asText()).isEqualTo("SH600519");
        assertThat(subjects.get(0).path("before").asDouble()).isEqualTo(55.0);
        assertThat(subjects.get(0).path("after").asDouble()).isEqualTo(70.0);
    }

    @Test
    void tick_scannedEventNotRescanned_consumedOncePerEventId() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())))
                .thenReturn(List.of()); // 第二轮扫描：LEFT JOIN 判重后为空（仓储语义，此处以返回值模拟）
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of(new SubjectScore(1, "SH600519", 55.0)));
        stubRecompute();
        stubTopAndPool(70.0);

        service.tick();
        service.tick();

        // 判重键 = event_id：每事件 insertScanned 恰一次、重算恰一轮
        verify(logRepository).insertScanned(any(ReevalEvent.class), anyString());
        verify(factorSnapshotService).recomputeIncremental(any(), anyCollection());
    }

    @Test
    void tick_twoEventsSameWindow_mergeBySubjectUnionSingleRecompute() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                event(101L, List.of("SH600519"), List.of()),
                                event(102L, List.of("SZ000001", "SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of());
        stubRecompute();
        stubTopAndPool(70.0);

        service.tick();

        // 合并去抖窗 = tick 间隔本身：同窗两事件按标的并集一轮重算（每标的每 tick 恰一次）
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(factorSnapshotService)
                .recomputeIncremental(any(LocalDate.class), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
        // 两事件同一轮留痕（一轮一状态）
        ArgumentCaptor<Collection<Long>> roundCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(logRepository)
                .markRecomputed(roundCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(roundCaptor.getValue()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void tick_industryProjection_sameSourceAsF2MemberEdges() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of(), List.of("电子"))));
        when(snapshotRepository.findIndustryMembers())
                .thenReturn(
                        List.of(
                                new FactorSnapshotRepository.IndustryMemberRow("SH600519", "消费电子"),
                                new FactorSnapshotRepository.IndustryMemberRow(
                                        "SZ300750", "医疗服务")));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of());
        stubRecompute();
        when(marketTopRepository.findLatest(TODAY.toString())).thenReturn(Optional.empty());
        when(snapshotRepository.findPoolRowsByDate(TODAY.toString()))
                .thenReturn(List.of(poolRow(1, "SH600519", "贵州茅台", 70.0)));

        service.tick();

        // 路 C 同源投影：东财「消费电子」→ 申万「电子」命中；「医疗服务」→ 医药生物不命中不进受影响集
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(factorSnapshotService)
                .recomputeIncremental(any(LocalDate.class), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(1L);
    }

    @Test
    void tick_subjectCodeOutsideActivePool_ignored() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("HK00700"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of());
        stubRecompute();

        service.tick();

        // 池外代码不进受影响集（受影响集 = 池内标的 ∪ 池内投影）；空集重算零行零留痕更新
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(factorSnapshotService)
                .recomputeIncremental(any(LocalDate.class), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).isEmpty();
    }

    // ---- 判定与终态 ----

    @Test
    void tick_judgeNotPassed_marksNoLink() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of(new SubjectScore(1, "SH600519", 55.0)));
        stubRecompute();
        stubTopAndPool(60.3); // 在榜 final 60 / 受影响 total 60.3（< 60 + 0.5 → 未过阈）

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("nopass");
        // 防抖红线：不过阈不重排——终态 NO_LINK
        verify(logRepository)
                .markStatus(anyCollection(), org.mockito.Mockito.eq("NO_LINK"), anyString());
    }

    @Test
    void tick_judgePassed_linksVersionAndMarksLinked() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of(new SubjectScore(1, "SH600519", 55.0)));
        stubRecompute();
        stubTopAndPool(70.0); // 70 > 60 + 0.5 → 过阈
        when(incrementalTopService.link(any(), any(), anyCollection())).thenReturn(2);

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("link=v:2");
        var verdictCaptor =
                ArgumentCaptor.forClass(
                        com.info.platform.domain.markettop.SqueezeJudge.Verdict.class);
        verify(incrementalTopService)
                .link(any(LocalDate.class), verdictCaptor.capture(), anyCollection());
        assertThat(verdictCaptor.getValue().passed()).isTrue();
        verify(logRepository).markLinked(anyCollection(), org.mockito.Mockito.eq(2), any(), any());
    }

    @Test
    void tick_withinLinkInterval_marksDeferredWithoutLink() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of(new SubjectScore(1, "SH600519", 55.0)));
        stubRecompute();
        stubTopAndPool(70.0);
        // 上一 EVENT 版本 5 分钟前（< 联动间隔 10min → DEFERRED 防抖）
        when(logRepository.findLastEventVersionAt(TODAY.toString()))
                .thenReturn(Optional.of(NOW.minusSeconds(300).toString()));

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("defer=interval");
        verify(logRepository)
                .markStatus(anyCollection(), org.mockito.Mockito.eq("DEFERRED"), anyString());
        verify(incrementalTopService, never()).link(any(), any(), anyCollection());
        verify(logRepository, never()).markLinked(anyCollection(), anyInt(), any(), any());
    }

    @Test
    void tick_pendingRoundWithoutNewEvents_relinksWithoutRecompute() {
        when(logRepository.findPendingLink())
                .thenReturn(
                        List.of(
                                new PendingLink(
                                        201L,
                                        "[{\"id\":1,\"code\":\"SH600519\",\"before\":50.0,\"after\":70.0}]")));
        when(marketTopRepository.findLatest(TODAY.toString()))
                .thenReturn(Optional.of(topVersion(60, 60, 60, 60, 60, 60, 60, 60, 60, 60)));
        when(snapshotRepository.findPoolRowsByDate(TODAY.toString()))
                .thenReturn(List.of(poolRow(1, "SH600519", "贵州茅台", 70.0)));
        when(incrementalTopService.link(any(), any(), anyCollection())).thenReturn(3);

        IncrementalReevalService.ReevalReport report = service.tick();

        assertThat(report.detail()).contains("link=v:3");
        // 挂起轮重走联动段：不再重算（数据已就位），事件留痕随联动收口
        verify(factorSnapshotService, never()).recomputeIncremental(any(), anyCollection());
        verify(logRepository).markLinked(anyCollection(), org.mockito.Mockito.eq(3), any(), any());
    }

    @Test
    void tick_judgeNotPassedOnPendingRound_marksNoLinkTerminally() {
        when(logRepository.findPendingLink())
                .thenReturn(
                        List.of(
                                new PendingLink(
                                        201L,
                                        "[{\"id\":1,\"code\":\"SH600519\",\"before\":70.0,\"after\":60.3}]")));
        when(marketTopRepository.findLatest(TODAY.toString()))
                .thenReturn(Optional.of(topVersion(60, 60, 60, 60, 60, 60, 60, 60, 60, 60)));
        when(snapshotRepository.findPoolRowsByDate(TODAY.toString()))
                .thenReturn(List.of(poolRow(1, "SH600519", "贵州茅台", 60.3)));

        IncrementalReevalService.ReevalReport report = service.tick();

        // 挂起轮重判未过阈（分数回落）→ NO_LINK 终态不再重试
        assertThat(report.detail()).contains("nopass");
        verify(logRepository)
                .markStatus(anyCollection(), org.mockito.Mockito.eq("NO_LINK"), anyString());
    }

    @Test
    void tick_minImportanceThresholdAndBufferWindowPassedToScan() {
        service.tick();

        // 阈值等级热读传递（缺省 HIGH）；落库缓冲 20s（缓冲内事件留待下轮）+ 24h 补跑窗
        verify(logRepository)
                .findUnconsumedEvents(
                        org.mockito.Mockito.eq(Importance.HIGH),
                        org.mockito.Mockito.eq(NOW.minusSeconds(20)),
                        org.mockito.Mockito.eq(NOW.minusSeconds(24 * 3600L)),
                        anyInt());
    }

    // ---- 异常路径（FAILED 旁路不上抛，次日全量自愈） ----

    @Test
    void tick_recomputeThrows_marksFailedAndDoesNotPropagate() {
        when(logRepository.findUnconsumedEvents(any(), any(), any(), anyInt()))
                .thenReturn(List.of(event(101L, List.of("SH600519"), List.of())));
        when(logRepository.findScoresBySubjectIds(anyString(), anyCollection()))
                .thenReturn(List.of());
        when(factorSnapshotService.recomputeIncremental(any(), anyCollection()))
                .thenThrow(new IllegalStateException("投影查询失败"));

        assertThatCode(() -> service.tick()).doesNotThrowAnyException();

        verify(logRepository).markFailed(anyCollection(), anyString(), anyString());
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
