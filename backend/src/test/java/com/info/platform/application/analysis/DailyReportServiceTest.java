package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.HeatWindow;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.IndustryHeatSnapshot;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.push.IndustryReportReadyEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * DailyReportService 单测（T124，方案 §4.5）：统计注入准确性（数字全部来自统计 SQL——模板渲染与 content 断言）、AI 叙述 + 统计组合、FUSED
 * 跳过（DEGRADED 保留）、LLM 失败降级纯统计版、幂等（SUCCESS 直返 / FAILED 替换重生成）、定时补跑窗口、 成功后事件发布。LLM 全 Mock 零外呼。AAA 结构。
 */
class DailyReportServiceTest {

    /** 定时口径锚点：上海 2026-09-23 08:00（= UTC 00:00）→ 昨日 = 2026-09-22。 */
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    /** 昨日上海日窗边界（UTC ISO 文本）：[2026-09-22T00:00 SH, 2026-09-23T00:00 SH)。 */
    private static final String YESTERDAY = "2026-09-22";

    private static final String YESTERDAY_FROM = "2026-09-21T16:00:00Z";

    private static final String YESTERDAY_TO = "2026-09-22T16:00:00Z";

    private static final ObjectMapper JSON = new ObjectMapper();

    private DailyReportRepository reportRepository;
    private HeatSnapshotRepository heatSnapshotRepository;
    private PipelineGuardService guardService;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private ApplicationEventPublisher eventPublisher;
    private DailyReportService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(DailyReportRepository.class);
        heatSnapshotRepository = mock(HeatSnapshotRepository.class);
        guardService = mock(PipelineGuardService.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        when(promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_DAILY))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                7L,
                                BriefType.INDUSTRY_DAILY,
                                "v1.0",
                                """
                                ---SYSTEM---
                                你是财经行业分析师。只能基于给定统计数据叙述。输出 json。
                                ---USER---
                                报告日期：{{reportDate}}
                                昨日行业统计（含容器）：{{industryStats}}
                                Top5 行业与代表事件：{{topEvents}}
                                请输出 json。
                                """,
                                1));
        when(promptTemplateService.render(any(), any()))
                .thenAnswer(
                        invocation -> {
                            PromptTemplate template = invocation.getArgument(0);
                            Map<String, String> ctx = invocation.getArgument(1);
                            String[] parts = template.getTemplate().split("---USER---");
                            String user =
                                    ctx.entrySet().stream()
                                            .reduce(
                                                    parts[1],
                                                    (text, entry) ->
                                                            text.replace(
                                                                    "{{" + entry.getKey() + "}}",
                                                                    entry.getValue()),
                                                    (a, b) -> b);
                            return List.of(
                                    new ChatMessage("system", parts[0].trim()),
                                    new ChatMessage("user", user.trim()));
                        });
        service =
                new DailyReportService(
                        reportRepository,
                        heatSnapshotRepository,
                        guardService,
                        new PipelineSettings(configService, JSON),
                        llmGateway,
                        promptTemplateService,
                        eventPublisher,
                        JSON,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // —— 数据面 fixture ——

    private static DailyReportRepository.IndustryCount news(String category, long count) {
        return new DailyReportRepository.IndustryCount(category, count);
    }

    private static DailyReportRepository.ReportEvent event(
            long eventId, String summary, Importance importance, List<String> industries) {
        return new DailyReportRepository.ReportEvent(
                eventId,
                eventId + 1000,
                EventType.POLICY_RELEASE,
                summary,
                industries,
                Direction.BULLISH,
                importance,
                "原文引用" + eventId,
                "[{\"label\":\"金额\",\"value\":\"100亿\",\"unit\":\"\"}]",
                NOW);
    }

    private void stubStats() {
        // 昨日窗：银行 12 条 / 电子 8 条 / 容器宏观 5 条；事件 2 条（1 HIGH 银行 + 1 MEDIUM 电子扩散房地产）
        when(reportRepository.countNewsByIndustry(eq(YESTERDAY_FROM), eq(YESTERDAY_TO)))
                .thenReturn(List.of(news("银行", 12), news("电子", 8), news("宏观", 5)));
        when(reportRepository.findEventsByDate(eq(YESTERDAY), any(Integer.class)))
                .thenReturn(
                        List.of(
                                event(11, "央行开展买断式逆回购", Importance.HIGH, List.of("银行")),
                                event(12, "芯片新产能落地", Importance.MEDIUM, List.of("电子", "房地产"))));
        when(heatSnapshotRepository.findBoard(HeatWindow.H24))
                .thenReturn(
                        List.of(
                                IndustryHeatSnapshot.reconstruct(
                                        1L,
                                        "银行",
                                        HeatWindow.H24,
                                        45.0,
                                        40.0,
                                        12.5,
                                        12,
                                        1,
                                        "heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h",
                                        NOW,
                                        NOW,
                                        NOW),
                                IndustryHeatSnapshot.reconstruct(
                                        2L,
                                        "电子",
                                        HeatWindow.H24,
                                        30.0,
                                        20.0,
                                        50.0,
                                        8,
                                        1,
                                        "heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h",
                                        NOW,
                                        NOW,
                                        NOW)));
    }

    private static String narrativeJson() {
        return """
        {"summary":"昨日银行与电子行业活跃。","topIndustries":[{"industry":"银行","commentary":"资金面宽松带动。"},\
        {"industry":"电子","commentary":"新产能落地。"}],"watchPoints":["关注回购规模","关注芯片产能","关注指数变化"],\
        "disclaimer":"AI 分析仅供参考"}
        """;
    }

    private static JsonNode readJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("content 契约须为合法 JSON: " + json, e);
        }
    }

    @Test
    void generateFor_injectsStatsIntoPrompt_numbersFromSqlOnly() {
        // Arrange：统计注入准确性——渲染上下文的数字与统计 SQL 产出一致（AI 只写叙述）
        stubStats();
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(100, 200), null, "test"));

        // Act
        DailyReportService.GenerationOutcome outcome = service.generateFor(YESTERDAY);

        // Assert：模板占位符注入统计行（数字来自 SQL，非 AI 生成）
        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), ctxCaptor.capture());
        Map<String, String> ctx = ctxCaptor.getValue();
        assertThat(ctx.get("reportDate")).isEqualTo(YESTERDAY);
        assertThat(ctx.get("industryStats"))
                .contains("银行")
                .contains("12")
                .contains("电子")
                .contains("宏观");
        assertThat(ctx.get("topEvents")).contains("HIGH").contains("央行开展买断式逆回购").contains("原文引用11");
        // 单次 LLM 调用：scene="7"、管道工厂（cacheable=false / JSON mode / 温度 0.1）
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway, times(1)).chat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().briefTypeKey()).isEqualTo("7");
        assertThat(requestCaptor.getValue().cacheable()).isFalse();
        assertThat(requestCaptor.getValue().responseFormatType()).isEqualTo("json_object");
        assertThat(requestCaptor.getValue().temperature()).isEqualTo(0.1);
        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.totalNews()).isEqualTo(25L);
        assertThat(outcome.totalEvents()).isEqualTo(2L);
        assertThat(outcome.narrativeDegraded()).isFalse();
    }

    @Test
    void generateFor_combinesNarrativeWithStats_contentContract() {
        // Arrange：AI 叙述 + 统计组合——content 内数字 = 统计 SQL 值、叙述来自模型、disclaimer 固定
        stubStats();
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(100, 200), null, "test"));

        // Act
        service.generateFor(YESTERDAY);

        // Assert
        ArgumentCaptor<IndustryDailyReport> captor =
                ArgumentCaptor.forClass(IndustryDailyReport.class);
        verify(reportRepository).upsert(captor.capture());
        IndustryDailyReport saved = captor.getValue();
        assertThat(saved.getReportDate()).isEqualTo(YESTERDAY);
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(saved.getPromptVersion()).isEqualTo("v1.0");
        assertThat(saved.getBasis()).contains("heat-v1:k1=10").contains("cost-v1:initial");
        JsonNode content = readJson(saved.getContent());
        assertThat(content.get("summary").asText()).contains("银行");
        assertThat(content.get("narrativeDegraded").asBoolean()).isFalse();
        assertThat(content.get("totalNews").asLong()).isEqualTo(25L);
        assertThat(content.get("totalEvents").asLong()).isEqualTo(2L);
        assertThat(content.get("disclaimer").asText()).isEqualTo(DailyReportService.DISCLAIMER);
        assertThat(content.get("containerCounts").get("宏观").asLong()).isEqualTo(5L);
        assertThat(content.get("industryCounts").get("银行").asLong()).isEqualTo(12L);
        // topIndustries：统计数字 + AI commentary 合并；heat 来自快照
        JsonNode top = content.get("topIndustries");
        assertThat(top.size()).isLessThanOrEqualTo(5);
        assertThat(top.get(0).get("industry").asText()).isEqualTo("银行");
        assertThat(top.get(0).get("newsCount").asLong()).isEqualTo(12L);
        assertThat(top.get(0).get("eventCount").asLong()).isEqualTo(1L);
        assertThat(top.get(0).get("heatScore").asDouble()).isEqualTo(45.0);
        assertThat(top.get(0).get("commentary").asText()).contains("资金面");
        assertThat(top.get(0).get("refEventIds").toString()).contains("11");
        // 事件精选：重要度降序 ≤10，含 quote/figures 可回溯
        JsonNode events = content.get("events");
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.get(0).get("eventId").asLong()).isEqualTo(11L);
        assertThat(events.get(0).get("importance").asText()).isEqualTo("HIGH");
        assertThat(events.get(0).get("quote").asText()).isEqualTo("原文引用11");
        assertThat(events.get(0).get("figures").toString()).contains("100亿");
        // watchPoints 叙述保留
        assertThat(content.get("watchPoints").size()).isEqualTo(3);
        // heat_top 快照留存（生成时点 H24 榜）
        JsonNode heatTop = readJson(saved.getHeatTop());
        assertThat(heatTop.size()).isEqualTo(2);
        assertThat(heatTop.get(0).get("industry").asText()).isEqualTo("银行");
        assertThat(heatTop.get(0).get("heatScore").asDouble()).isEqualTo(45.0);
    }

    @Test
    void generateFor_llmFailure_degradesToPureStatsHonestlyMarked() {
        // Arrange：LLM 失败 → 纯统计版 SUCCESS（narrativeDegraded=true 如实标注 + error_message 留痕）
        stubStats();
        when(llmGateway.chat(any())).thenThrow(new RuntimeException("provider 不可用"));

        // Act
        DailyReportService.GenerationOutcome outcome = service.generateFor(YESTERDAY);

        // Assert
        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.narrativeDegraded()).isTrue();
        ArgumentCaptor<IndustryDailyReport> captor =
                ArgumentCaptor.forClass(IndustryDailyReport.class);
        verify(reportRepository).upsert(captor.capture());
        IndustryDailyReport saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(saved.getErrorMessage()).contains("provider 不可用");
        JsonNode content = readJson(saved.getContent());
        assertThat(content.get("narrativeDegraded").asBoolean()).isTrue();
        assertThat(content.get("summary").asText()).contains("统计");
        assertThat(content.get("totalNews").asLong()).isEqualTo(25L);
        // 降级日不发完成通知（叙述缺失，避免误导晨读）
        verify(eventPublisher, never()).publishEvent(any(IndustryReportReadyEvent.class));
    }

    @Test
    void generateFor_fusedLevel_skipsWithTrace_degradedRetained() {
        // Arrange：FUSED 跳过（不落库不调用 LLM）；DEGRADED 保留（照常生成）
        stubStats();
        when(guardService.currentLevel()).thenReturn(GuardLevel.FUSED);
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(1, 1), null, "test"));

        // Act
        DailyReportService.GenerationOutcome fusedOutcome = service.generateFor(YESTERDAY);

        // Assert：FUSED 全跳（留痕 skipped）
        assertThat(fusedOutcome.skipped()).isTrue();
        assertThat(fusedOutcome.reason()).contains("fused");
        verify(reportRepository, never()).upsert(any());
        verify(llmGateway, never()).chat(any());

        // DEGRADED 保留：日报存活（裁决 5——REQ 拍板四-1「暂停 L2 与日报外的 AI 生成」）
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);
        DailyReportService.GenerationOutcome degradedOutcome = service.generateFor(YESTERDAY);
        assertThat(degradedOutcome.skipped()).isFalse();
        assertThat(degradedOutcome.status()).isEqualTo("SUCCESS");
    }

    @Test
    void generateFor_alreadySuccess_returnsIdempotentlyWithoutLlm() {
        // Arrange：当日已 SUCCESS → 直返跳过（幂等红线；已 SUCCESS 重试 409/30077 由端点把守）
        stubStats();
        when(reportRepository.findByReportDate(YESTERDAY))
                .thenReturn(
                        Optional.of(
                                IndustryDailyReport.success(
                                        YESTERDAY, "{}", "[]", null, "v1.0", "b", NOW)));

        // Act
        DailyReportService.GenerationOutcome outcome = service.generateFor(YESTERDAY);

        // Assert
        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.reason()).contains("already-success");
        verify(llmGateway, never()).chat(any());
        verify(reportRepository, never()).upsert(any());
    }

    @Test
    void generateFor_failedRow_regeneratesByReplace() {
        // Arrange：FAILED 行 → 重生成整行替换（UNIQUE(report_date) UPSERT 语义）
        stubStats();
        when(reportRepository.findByReportDate(YESTERDAY))
                .thenReturn(Optional.of(IndustryDailyReport.failed(YESTERDAY, "旧失败", NOW)));
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(1, 1), null, "test"));
        when(reportRepository.upsert(any()))
                .thenAnswer(
                        invocation ->
                                IndustryDailyReport.success(
                                        YESTERDAY, "{}", "[]", null, "v1.0", "b", NOW));

        // Act
        DailyReportService.GenerationOutcome outcome = service.generateFor(YESTERDAY);

        // Assert
        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.status()).isEqualTo("SUCCESS");
        verify(reportRepository).upsert(any());
    }

    @Test
    void generateFor_success_publishesReadyEvent() {
        // Arrange：叙述正常生成 → INDUSTRY_REPORT(9) 完成事件（PushService Should 消费）
        stubStats();
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(1, 1), null, "test"));
        when(reportRepository.upsert(any()))
                .thenAnswer(
                        invocation ->
                                IndustryDailyReport.success(
                                        YESTERDAY, "{}", "[]", null, "v1.0", "b", NOW));

        // Act
        service.generateFor(YESTERDAY);

        // Assert
        ArgumentCaptor<IndustryReportReadyEvent> captor =
                ArgumentCaptor.forClass(IndustryReportReadyEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().reportDate()).isEqualTo(YESTERDAY);
        assertThat(captor.getValue().narrativeDegraded()).isFalse();
    }

    @Test
    void runScheduledWindow_coversYesterdayAndBackfillDay() {
        // Arrange：定时补跑窗口 [前日, 昨日]——正常只有昨日缺行；FUSED 次日补 = 前日缺行也生成
        stubStats();
        when(reportRepository.findByReportDate(anyString())).thenReturn(Optional.empty());
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(1, 1), null, "test"));
        when(reportRepository.upsert(any()))
                .thenAnswer(
                        invocation ->
                                IndustryDailyReport.success(
                                        ((IndustryDailyReport) invocation.getArgument(0))
                                                .getReportDate(),
                                        "{}",
                                        "[]",
                                        null,
                                        "v1.0",
                                        "b",
                                        NOW));

        // Act
        DailyReportService.WindowReport report = service.runScheduledWindow();

        // Assert：两日均生成（oldest first），留痕含日期
        ArgumentCaptor<IndustryDailyReport> captor =
                ArgumentCaptor.forClass(IndustryDailyReport.class);
        verify(reportRepository, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues().stream().map(IndustryDailyReport::getReportDate))
                .containsExactly("2026-09-21", YESTERDAY);
        assertThat(report.generated()).isEqualTo(2);
        assertThat(report.detail()).contains(YESTERDAY).contains("2026-09-21");
    }

    @Test
    void runScheduledWindow_fusedSkipsWholeWindow_withTrace() {
        // Arrange：FUSED → 整窗跳过留痕（次日窗口滚动恢复后由 [前日] 补跑覆盖）
        stubStats();
        when(guardService.currentLevel()).thenReturn(GuardLevel.FUSED);

        // Act
        DailyReportService.WindowReport report = service.runScheduledWindow();

        // Assert
        assertThat(report.generated()).isZero();
        assertThat(report.skipped()).isEqualTo(2);
        assertThat(report.detail()).contains("fused");
        verify(reportRepository, never()).upsert(any());
    }

    @Test
    void providedDescriptors_matchRenderedContextKeys() {
        // T46 同源闸门：provided() 与渲染 ctx.put 键集一致（防漂移）
        stubStats();
        when(llmGateway.chat(any()))
                .thenReturn(new LlmResponse(narrativeJson(), new LlmUsage(1, 1), null, "test"));
        when(reportRepository.upsert(any()))
                .thenAnswer(
                        invocation ->
                                IndustryDailyReport.success(
                                        YESTERDAY, "{}", "[]", null, "v1.0", "b", NOW));

        service.generateFor(YESTERDAY);

        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        service.provided().stream().map(PlaceholderDescriptor::key).toList());
        assertThat(service.briefTypes()).containsExactly(BriefType.INDUSTRY_DAILY);
    }
}
