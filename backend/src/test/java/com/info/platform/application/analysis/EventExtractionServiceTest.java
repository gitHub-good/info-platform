package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.L2Status;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AiExclusion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * EventExtractionService 单测（T122，方案 §4.4 / §6 L2 组）：渲染契约（today/batchSize/items 含归类结果与候选公司）、
 * 调用契约（scene=6 + cacheable=false）、events+skipped 解析与落库（EXTRACTED/NO_EVENT/affected 越界丢弃/subjects 回联
 * code 可空/ eventTime 回退 published_at/event_date 上海日界）、配额截断 DEFERRED 如实统计、次日先还旧账、对半拆批、网络失败本 tick 放弃、
 * aiExclusion=L2 源过滤。LLM 全 mock 零外呼。AAA 结构。
 */
class EventExtractionServiceTest {

    /** 上海时区 2026-09-22 16:00 = UTC 08:00（当日零点上海 2026-09-22T00:00+08:00 = UTC 前一日 16:00）。 */
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private NewsAnalysisRepository repository;
    private EventItemRepository eventRepository;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private SubjectMatcher subjectMatcher;
    private AiExclusionResolver exclusionResolver;
    private EventExtractionService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        eventRepository = mock(EventItemRepository.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        subjectMatcher = mock(SubjectMatcher.class);
        when(subjectMatcher.match(anyString(), anyString())).thenReturn(List.of());
        exclusionResolver = mock(AiExclusionResolver.class);
        when(exclusionResolver.excludedSourceIds(any())).thenReturn(List.of());
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new EventExtractionService(
                        repository,
                        eventRepository,
                        llmGateway,
                        promptTemplateService,
                        subjectMatcher,
                        exclusionResolver,
                        settings,
                        Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")),
                        new ObjectMapper());
        when(promptTemplateService.loadActiveTemplate(BriefType.L2_EXTRACT))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                6L,
                                BriefType.L2_EXTRACT,
                                "v1.0",
                                "---SYSTEM---\njson\n---USER---\n{{today}}\n{{batchSize}}\n{{items}}",
                                1));
        when(promptTemplateService.render(any(), any()))
                .thenReturn(
                        List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "ctx")));
        when(eventRepository.upsert(any(EventItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.applyL2Result(any())).thenReturn(1);
    }

    private static NewsAnalysisRepository.L2Candidate candidate(
            long id, String title, String sourceCategory, L2Status status) {
        return new NewsAnalysisRepository.L2Candidate(
                id,
                title,
                "摘要：" + title,
                "新浪财经",
                sourceCategory,
                "银行",
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600),
                status,
                null);
    }

    private static String event(long id, String type, String industries, String importance) {
        return "{\"id\":"
                + id
                + ",\"type\":\""
                + type
                + "\",\"summary\":\"事件摘要"
                + id
                + "\",\"industries\":"
                + industries
                + ",\"direction\":\"BULLISH\",\"importance\":\""
                + importance
                + "\",\"figures\":[{\"label\":\"净利润同比\",\"value\":\"+80%\",\"unit\":\"\"}],"
                + "\"subjects\":[{\"code\":\"SH600000\",\"name\":\"某公司\",\"industry\":\"食品饮料\"}],"
                + "\"quote\":\"净利润同比增长80%\",\"eventTime\":null}";
    }

    private static String response(String eventsJson, String skippedJson) {
        return "{\"events\":[" + eventsJson + "],\"skipped\":[" + skippedJson + "]}";
    }

    private static LlmResponse llmResponse(String content) {
        return new LlmResponse(
                content, new LlmUsage(100, 50), LlmProvider.DEEPSEEK, "deepseek-flash");
    }

    /** 常规候选（标题含「业绩预告」强触发 + 快讯 1.5 → 分 3.5 ≥ 2.5 命中）。 */
    private static NewsAnalysisRepository.L2Candidate hit(long id) {
        return candidate(id, "某公司业绩预告" + id, "快讯", L2Status.SKIP);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Double> capturedScores() {
        ArgumentCaptor<Map<Long, Double>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repository, atLeastOnce()).updateImportanceScores(captor.capture());
        return captor.getValue();
    }

    // ---- 渲染与调用契约 ----

    @Test
    void extractBatch_happyPath_rendersCallsAndPersists() {
        // Arrange
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                response(event(1, "EARNINGS_FORECAST", "[\"银行\"]", "HIGH"), "2")));

        // Act
        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1), hit(2)));

        // Assert：批结果（1 提取 + 1 无事件）
        assertThat(outcome.extracted()).isEqualTo(1);
        assertThat(outcome.noEvent()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();

        // 调用契约：scene=6 + cacheable=false + 管道采样
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(requestCaptor.capture());
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.briefTypeKey()).isEqualTo("6");
        assertThat(request.cacheable()).isFalse();
        assertThat(request.temperature()).isEqualTo(0.1);
        assertThat(request.maxTokens()).isEqualTo(8192);

        // 渲染上下文：today（上海日期）+ batchSize + items（含主分类与候选公司）
        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), ctxCaptor.capture());
        Map<String, String> context = ctxCaptor.getValue();
        assertThat(context.get("today")).isEqualTo("2026-09-22");
        assertThat(context.get("batchSize")).isEqualTo("2");
        assertThat(context.get("items"))
                .contains("\"id\":1")
                .contains("\"main\":\"银行\"")
                .contains("candidates");

        // event_item 落库字段（回联/关键数字/引用可回溯）
        ArgumentCaptor<EventItem> itemCaptor = ArgumentCaptor.forClass(EventItem.class);
        verify(eventRepository).upsert(itemCaptor.capture());
        EventItem item = itemCaptor.getValue();
        assertThat(item.getNewsId()).isEqualTo(1);
        assertThat(item.getEventType()).isEqualTo(EventType.EARNINGS_FORECAST);
        assertThat(item.getAffectedIndustries()).containsExactly("银行");
        assertThat(item.getDirection()).isEqualTo(Direction.BULLISH);
        assertThat(item.getImportance()).isEqualTo(Importance.HIGH);
        assertThat(item.getKeyFigures()).hasSize(1);
        assertThat(item.getSubjects()).hasSize(1);
        assertThat(item.getQuote()).isEqualTo("净利润同比增长80%");
        assertThat(item.getEventDate()).isEqualTo("2026-09-22"); // 上海日界
        assertThat(item.getEventTime())
                .isEqualTo(NOW.minusSeconds(3600)); // eventTime null → published_at

        // l2_status 推进
        verify(repository)
                .applyL2Result(new NewsAnalysisRepository.L2Write(1L, L2Status.EXTRACTED));
        verify(repository).applyL2Result(new NewsAnalysisRepository.L2Write(2L, L2Status.NO_EVENT));
    }

    @Test
    void extractBatch_invalidIndustryElement_droppedNotWholeRowFailed() {
        // affected 含容器「宏观」+ 越界「太空采矿」+ 合法「银行」→ 只留银行（不整条失败）
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                response(
                                        event(
                                                1,
                                                "POLICY_RELEASE",
                                                "[\"宏观\",\"太空采矿\",\"银行\"]",
                                                "MEDIUM"),
                                        "")));

        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1)));

        assertThat(outcome.extracted()).isEqualTo(1);
        ArgumentCaptor<EventItem> captor = ArgumentCaptor.forClass(EventItem.class);
        verify(eventRepository).upsert(captor.capture());
        assertThat(captor.getValue().getAffectedIndustries()).containsExactly("银行");
    }

    @Test
    void extractBatch_subjectWithoutCode_keptNameOnly() {
        // 未回联公司按 {"code":null,"name":...} 输出 → code 可空保留
        String row =
                "{\"id\":1,\"type\":\"MA_MERGER\",\"summary\":\"并购\",\"industries\":[\"银行\"],"
                        + "\"direction\":\"NEUTRAL\",\"importance\":\"LOW\",\"figures\":[],"
                        + "\"subjects\":[{\"code\":null,\"name\":\"未上市公司\",\"industry\":null}],"
                        + "\"quote\":\"拟收购\",\"eventTime\":\"2026-09-22T01:00:00Z\"}";
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse(response(row, "")));

        service.extractBatch(List.of(hit(1)));

        ArgumentCaptor<EventItem> captor = ArgumentCaptor.forClass(EventItem.class);
        verify(eventRepository).upsert(captor.capture());
        assertThat(captor.getValue().getSubjects())
                .containsExactly(new EventItem.SubjectRef(null, "未上市公司", null));
        assertThat(captor.getValue().getEventTime())
                .isEqualTo(Instant.parse("2026-09-22T01:00:00Z"));
    }

    @Test
    void extractBatch_invalidEnumRow_brokenThenSplit() {
        // 首响应 type 越界（整批无进展）→ 对半拆批 → 单条合法落库
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                response(
                                        event(1, "NOT_A_TYPE", "[\"银行\"]", "HIGH")
                                                + ","
                                                + event(2, "NOT_A_TYPE", "[\"银行\"]", "HIGH"),
                                        "")),
                        llmResponse(
                                response(event(1, "EARNINGS_FORECAST", "[\"银行\"]", "HIGH"), "")),
                        llmResponse(response(event(2, "OTHER", "[\"银行\"]", "LOW"), "")));

        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1), hit(2)));

        assertThat(outcome.extracted()).isEqualTo(2);
        verify(llmGateway, times(3)).chat(any(LlmRequest.class)); // [2] → [1] + [1]
    }

    @Test
    void extractBatch_wholeBatchUnparseable_splitsToSingleThenFailed() {
        // 2 条批：首轮垃圾文本 → 拆 [1]+[1]；[1] 单条仍垃圾 → FAILED（attempts+1）；[2] 合法
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse("垃圾输出"),
                        llmResponse("还是垃圾"),
                        llmResponse(response(event(2, "OTHER", "[\"银行\"]", "LOW"), "")));

        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1), hit(2)));

        assertThat(outcome.extracted()).isEqualTo(1);
        assertThat(outcome.failed()).isEqualTo(1);
        verify(repository).markL2Failed(List.of(1L));
    }

    @Test
    void extractBatch_uncoveredId_brokenPartialRetry() {
        // 响应只覆盖 id=2（skipped），id=1 缺席 → 部分进展，余量重试一次
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(llmResponse(response("", "2")))
                .thenReturn(llmResponse(response("", "1")));

        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1), hit(2)));

        assertThat(outcome.noEvent()).isEqualTo(2);
        verify(llmGateway, times(2)).chat(any(LlmRequest.class));
    }

    @Test
    void extractBatch_llmException_marksFailedAndAbandons() {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("所有 LLM provider 均失败", List.of("deepseek"), null));

        EventExtractionService.BatchOutcome outcome = service.extractBatch(List.of(hit(1), hit(2)));

        assertThat(outcome.failed()).isEqualTo(2);
        verify(llmGateway, times(1)).chat(any(LlmRequest.class)); // 不连环重试
        verify(repository).markL2Failed(List.of(1L, 2L));
        verify(eventRepository, never()).upsert(any());
    }

    @Test
    void extractBatch_emptyBatch_silent() {
        assertThat(service.extractBatch(List.of()).extracted()).isZero();
        assertThat(service.extractBatch(null).failed()).isZero();
        verify(llmGateway, never()).chat(any());
    }

    // ---- L2 段编排（runL2Window：打分 → 配额 → 分批） ----

    @Test
    void runL2Window_scoresCandidates_andWritesImportanceScores() {
        NewsAnalysisRepository.L2Candidate belowThreshold =
                candidate(9, "普通行业动态标题", "媒体", L2Status.SKIP); // 媒体 1.0 < 2.5
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(hit(1), belowThreshold));
        when(repository.countL1DoneSince(anyString())).thenReturn(10L);
        when(repository.countL2ProcessedSince(anyString())).thenReturn(0L);
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                response(event(1, "EARNINGS_FORECAST", "[\"银行\"]", "HIGH"), "")));

        EventExtractionService.L2Report report = service.runL2Window();

        // 打分回写：两条候选都有分（低于阈值者不改状态仍 SKIP）
        Map<Long, Double> scores = capturedScores();
        assertThat(scores).containsKeys(1L, 9L);
        assertThat(scores.get(1L)).isEqualTo(3.5);
        assertThat(scores.get(9L)).isEqualTo(1.0);
        assertThat(report.extracted()).isEqualTo(1);
        verify(repository, never()).markL2Deferred(anyList());
    }

    @Test
    void runL2Window_quotaCap_defersTailAsStats() {
        // 当日 L1 DONE=10 → 配额 floor(0.2×10)=2；已处理 0 → 本轮限 2 条；3 条命中 → 尾部 1 条 DEFERRED
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(hit(1), hit(2), hit(3)));
        when(repository.countL1DoneSince(anyString())).thenReturn(10L);
        when(repository.countL2ProcessedSince(anyString())).thenReturn(0L);
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(llmResponse(response(event(1, "OTHER", "[\"银行\"]", "LOW"), "")))
                .thenReturn(llmResponse(response(event(2, "OTHER", "[\"银行\"]", "LOW"), "")));

        EventExtractionService.L2Report report = service.runL2Window();

        assertThat(report.deferred()).isEqualTo(1);
        verify(repository).markL2Selected(anyList());
        verify(repository).markL2Deferred(List.of(3L));
    }

    @Test
    void runL2Window_consumedQuotaCounted_deferredOldDebtFirst() {
        // 配额 2 已消耗 1 → 余 1；当日新账 + 昨日旧账各 1 → 旧账（DEFERRED）优先入选，新账 DEFERRED
        NewsAnalysisRepository.L2Candidate oldDebt =
                candidate(5, "昨日业绩预告旧账", "快讯", L2Status.DEFERRED);
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(hit(1), oldDebt));
        when(repository.countL1DoneSince(anyString())).thenReturn(10L);
        when(repository.countL2ProcessedSince(anyString())).thenReturn(1L);
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(llmResponse(response(event(5, "OTHER", "[\"银行\"]", "LOW"), "")));

        EventExtractionService.L2Report report = service.runL2Window();

        assertThat(report.extracted()).isEqualTo(1); // 旧账还清
        assertThat(report.deferred()).isEqualTo(1); // 新账让位如实统计
        verify(repository).markL2Deferred(List.of(1L));
        verify(repository, never()).markL2Deferred(List.of(5L));
    }

    @Test
    void runL2Window_sortsByScoreDescThenId() {
        // 同为命中：分高者优先占配额（配额 1，两条命中分 3.5 与 4.0[强触发×1+政策 2.0]）
        NewsAnalysisRepository.L2Candidate policyHit =
                candidate(2, "央行降准公告", "政策", L2Status.SKIP); // 2.0+2.0=4.0
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of(hit(1), policyHit)); // id=1 分 3.5 < id=2 分 4.0
        when(repository.countL1DoneSince(anyString())).thenReturn(5L); // 配额 1
        when(repository.countL2ProcessedSince(anyString())).thenReturn(0L);
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(response(event(2, "POLICY_RELEASE", "[\"银行\"]", "HIGH"), "")));

        EventExtractionService.L2Report report = service.runL2Window();

        assertThat(report.extracted()).isEqualTo(1);
        verify(repository).markL2Deferred(List.of(1L)); // 低分新账让位
    }

    @Test
    void runL2Window_excludedSourceFilteredByRepository() {
        // 排除清单（aiExclusion=L2 源，T125 承载）原样下传仓储查询：L2 档源不产事件（照常归类照常计热度资讯量）
        when(exclusionResolver.excludedSourceIds(AiExclusion.L2)).thenReturn(List.of(21L, 22L));
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());
        when(repository.countL1DoneSince(anyString())).thenReturn(0L);

        service.runL2Window();

        verify(repository)
                .findL2Candidates(
                        anyString(), anyString(), anyInt(), eq(List.of(21L, 22L)), anyInt());
    }

    @Test
    void runL2Window_emptyCandidates_zeroReportNoCalls() {
        when(repository.findL2Candidates(anyString(), anyString(), anyInt(), anyList(), anyInt()))
                .thenReturn(List.of());

        EventExtractionService.L2Report report = service.runL2Window();

        assertThat(report.detail()).isEqualTo("l2=extracted:0; no_event:0; failed:0; deferred:0");
        verify(llmGateway, never()).chat(any());
        verify(repository, never()).markL2Deferred(anyList());
    }

    // ---- 占位符注册（ADR-0022 同源闸门） ----

    @Test
    void placeholders_registeredForL2Extract() {
        assertThat(service.briefTypes()).containsExactly(BriefType.L2_EXTRACT);
        assertThat(service.provided())
                .extracting("key")
                .containsExactly("today", "batchSize", "items");
    }
}
