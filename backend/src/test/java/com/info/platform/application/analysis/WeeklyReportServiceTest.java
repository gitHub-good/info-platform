package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.HeatSnapshotRepository;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import com.info.platform.domain.push.IndustryWeeklyReportReadyEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 行业周报生成服务单测（M17 T145/T146，REQ 拍板四/六 / 故事 2 场景 1/2/4/5）：周窗聚合口径（事件主键归并跨日去重 = event_item
 * 天然主键行集）、五区块结构完整、走向判断置信度 trend-v1 规则层锁定（AI 篡改拦截 → 模板兜底）、FUSED 跳过留痕、SUCCESS 幂等、LLM 失败模板降级、通知事件发布。
 */
class WeeklyReportServiceTest {

    /** 上海时区 2026-09-27（周日）20:00 = UTC 12:00——周报触发心智时刻。 */
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    /** 本周周一（Asia/Shanghai）：2026-09-21。 */
    private static final String WEEK_START = "2026-09-21";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WeeklyReportRepository weeklyRepository;
    private DailyReportRepository dailyRepository;
    private HeatSnapshotRepository heatSnapshotRepository;
    private PipelineGuardService guardService;
    private LlmGateway llmGateway;
    private ApplicationEventPublisher eventPublisher;
    private WeeklyReportService service;

    @BeforeEach
    void setUp() {
        weeklyRepository = mock(WeeklyReportRepository.class);
        dailyRepository = mock(DailyReportRepository.class);
        heatSnapshotRepository = mock(HeatSnapshotRepository.class);
        guardService = mock(PipelineGuardService.class);
        llmGateway = mock(LlmGateway.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        when(promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_WEEKLY))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                9L,
                                BriefType.INDUSTRY_WEEKLY,
                                "v1.0",
                                """
                                ---SYSTEM---
                                你是财经行业分析师。只能基于给定统计数据与规则信号叙述。输出 json。
                                ---USER---
                                周窗：{{weekStart}} ~ {{weekEnd}}
                                热度统计：{{heatStats}}
                                代表事件：{{topEvents}}
                                政策动向：{{policyLines}}
                                规则信号：{{trendSignals}}
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
                new WeeklyReportService(
                        weeklyRepository,
                        dailyRepository,
                        heatSnapshotRepository,
                        guardService,
                        new PipelineSettings(configService, JSON),
                        llmGateway,
                        promptTemplateService,
                        eventPublisher,
                        JSON,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        stubWeekData();
    }

    // —— 数据面 fixture：本周银行 3 事件（2 政策 + 1 业绩）+ 食品饮料 1 事件；热度条目仅本周窗 ——

    private void stubWeekData() {
        when(dailyRepository.countNewsByIndustry(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new DailyReportRepository.IndustryCount("银行", 12),
                                new DailyReportRepository.IndustryCount("食品饮料", 5)));
        when(weeklyRepository.findEventsBetween(anyString(), anyString(), anyInt()))
                .thenReturn(
                        List.of(
                                eventOf(1L, EventType.POLICY_RELEASE, "银行", Importance.HIGH),
                                eventOf(2L, EventType.POLICY_RELEASE, "银行", Importance.MEDIUM),
                                eventOf(3L, EventType.EARNINGS_FORECAST, "银行", Importance.MEDIUM),
                                eventOf(4L, EventType.MAJOR_CONTRACT, "食品饮料", Importance.LOW)));
        when(heatSnapshotRepository.findWindowItems(anyString(), anyString(), eq(Market.A_SHARE)))
                .thenReturn(
                        List.of(
                                new HeatSnapshotRepository.WindowItem(
                                        "银行",
                                        Instant.parse("2026-09-24T02:00:00Z"),
                                        Importance.HIGH,
                                        List.of("银行")),
                                new HeatSnapshotRepository.WindowItem(
                                        "银行",
                                        Instant.parse("2026-09-25T02:00:00Z"),
                                        Importance.MEDIUM,
                                        List.of("银行")),
                                new HeatSnapshotRepository.WindowItem(
                                        "银行",
                                        Instant.parse("2026-09-26T02:00:00Z"),
                                        Importance.LOW,
                                        List.of("银行"))));
        when(weeklyRepository.upsert(any(IndustryWeeklyReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static DailyReportRepository.ReportEvent eventOf(
            long id, EventType type, String industry, Importance importance) {
        return new DailyReportRepository.ReportEvent(
                id,
                1000L + id,
                type,
                type.displayName() + "事件" + id,
                List.of(industry),
                Direction.BULLISH,
                importance,
                "原文引用" + id,
                "[]",
                Instant.parse("2026-09-2" + (3 + (id % 5)) + "T02:00:00Z"),
                "周源" + id,
                "https://example.com/w/" + (1000L + id));
    }

    private static LlmResponse llmResponse(String content) {
        return new LlmResponse(
                content, new LlmUsage(200, 80), LlmProvider.DEEPSEEK, "deepseek-chat");
    }

    @Test
    @DisplayName("FUSED 跳过留痕（沿日报先例，不落库不调 LLM）")
    void generateFor_fused_skips() {
        when(guardService.currentLevel()).thenReturn(GuardLevel.FUSED);

        WeeklyReportService.GenerationOutcome outcome = service.generateFor(WEEK_START);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.reason()).isEqualTo("skipped(fused)");
        verify(weeklyRepository, never()).upsert(any());
        verify(llmGateway, never()).chat(any());
    }

    @Test
    @DisplayName("已 SUCCESS 幂等直返跳过")
    void generateFor_alreadySuccess_idempotent() {
        when(weeklyRepository.findByWeekStart(WEEK_START))
                .thenReturn(
                        Optional.of(
                                IndustryWeeklyReport.success(
                                        WEEK_START, "{}", "[]", null, "v1.0", "b", NOW)));

        WeeklyReportService.GenerationOutcome outcome = service.generateFor(WEEK_START);

        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.reason()).isEqualTo("already-success");
        verify(weeklyRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("主路径：五区块结构完整 + 走向判断置信度规则锁定 + 通知事件发布")
    void generateFor_happyPath_fiveBlocksTrendLockedAndNotice() throws Exception {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                """
                                {"summary":"本周银行板块显著升温","watchPoints":["关注货币政策延续性"],
                                 "trendNarratives":[{"industry":"银行","narrative":"银行板块本周热度显著升温，事件与政策密度同步抬升。","confidence":"HIGH"}]}
                                """));

        WeeklyReportService.GenerationOutcome outcome = service.generateFor(WEEK_START);

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.narrativeDegraded()).isFalse();

        ArgumentCaptor<IndustryWeeklyReport> captor =
                ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        JsonNode content = JSON.readTree(captor.getValue().getContent());
        // 五区块：热度总览 / 事件回顾 / 政策动向 / 下周关注点 / 走向判断
        assertThat(content.has("summary")).isTrue();
        assertThat(content.path("totalNews").asLong()).isEqualTo(17);
        assertThat(content.path("totalEvents").asLong()).isEqualTo(4);
        assertThat(content.path("topRisers").size()).isGreaterThan(0);
        assertThat(content.path("eventReview").size()).isEqualTo(4);
        assertThat(content.path("policyMoves").size()).isEqualTo(2); // 仅 POLICY_RELEASE
        // T163 溯源增量：事件回顾/政策动向逐条带 sourceName + newsUrl（新报告起升 A 级）
        assertThat(content.path("eventReview").get(0).path("sourceName").asText()).isEqualTo("周源1");
        assertThat(content.path("eventReview").get(0).path("newsUrl").asText())
                .isEqualTo("https://example.com/w/1001");
        assertThat(content.path("policyMoves").get(0).path("sourceName").asText()).isNotBlank();
        assertThat(content.path("policyMoves").get(0).path("newsUrl").asText())
                .startsWith("https://");
        assertThat(content.path("nextWeekWatch").size()).isGreaterThan(0);
        JsonNode trend = content.path("trendJudgement");
        assertThat(trend.path("basis").asText()).isEqualTo("trend-v1");
        assertThat(trend.path("items").size()).isGreaterThan(0);
        JsonNode bankTrend =
                trend.path("items").isArray()
                        ? findTrend(trend.path("items"), "银行")
                        : trend.path("items");
        // 置信度 = 规则层（delta=100 ≥50 + 事件密度 3 ≥3 且环比上升 → HIGH），AI 不可改
        assertThat(bankTrend.path("confidence").asText()).isEqualTo("HIGH");
        assertThat(bankTrend.path("signal").asText()).isEqualTo("HEATING");
        assertThat(bankTrend.path("narrativeSource").asText()).isEqualTo("LLM");
        assertThat(content.path("disclaimer").asText()).isEqualTo("AI 分析仅供参考");
        assertThat(captor.getValue().getBasis()).contains("trend-v1");

        verify(eventPublisher).publishEvent(any(IndustryWeeklyReportReadyEvent.class));
    }

    @Test
    @DisplayName("周窗聚合口径：事件按主键归并（event_item 一行一主线，跨日去重天然成立）")
    void generateFor_eventMergeByPrimaryKey() throws Exception {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                "{\"summary\":\"s\",\"watchPoints\":[],\"trendNarratives\":[]}"));

        service.generateFor(WEEK_START);

        ArgumentCaptor<IndustryWeeklyReport> captor =
                ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        JsonNode review = JSON.readTree(captor.getValue().getContent()).path("eventReview");
        assertThat(review.size()).isEqualTo(4); // 4 事件 4 行，无跨日重复行
        assertThat(review.get(0).path("eventId").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("LLM 失败：模板降级（narrativeDegraded=true，结构化数据直出，链路不死）")
    void generateFor_llmFails_templateDegrade() throws Exception {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("LLM 不可用", java.util.List.of("deepseek"), null));

        WeeklyReportService.GenerationOutcome outcome = service.generateFor(WEEK_START);

        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.narrativeDegraded()).isTrue();
        ArgumentCaptor<IndustryWeeklyReport> captor =
                ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        JsonNode content = JSON.readTree(captor.getValue().getContent());
        assertThat(content.path("narrativeDegraded").asBoolean()).isTrue();
        assertThat(content.path("summary").asText()).isNotBlank();
        assertThat(content.path("nextWeekWatch").size()).isGreaterThan(0);
        JsonNode items = content.path("trendJudgement").path("items");
        assertThat(items.size()).isGreaterThan(0);
        assertThat(items.get(0).path("narrativeSource").asText()).isEqualTo("TEMPLATE");
        // 降级版不发完成通知（沿日报先例——不误导用户）
        verify(eventPublisher, never()).publishEvent(any(IndustryWeeklyReportReadyEvent.class));
    }

    @Test
    @DisplayName("AI 篡改拦截：置信度与规则层不一致 → 该行业叙述模板兜底，置信度仍规则层值")
    void generateFor_aiTamperedConfidence_intercepted() throws Exception {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                """
                                {"summary":"s","watchPoints":["w"],
                                 "trendNarratives":[{"industry":"银行","narrative":"AI 篡改置信度的叙述。","confidence":"LOW"}]}
                                """));

        service.generateFor(WEEK_START);

        ArgumentCaptor<IndustryWeeklyReport> captor =
                ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        JsonNode bankTrend =
                findTrend(
                        JSON.readTree(captor.getValue().getContent())
                                .path("trendJudgement")
                                .path("items"),
                        "银行");
        assertThat(bankTrend.path("confidence").asText())
                .as("置信度恒为规则层值（AI 不可抬高或降低）")
                .isEqualTo("HIGH");
        assertThat(bankTrend.path("narrativeSource").asText()).isEqualTo("TEMPLATE");
        assertThat(bankTrend.path("narrative").asText()).doesNotContain("AI 篡改");
    }

    @Test
    @DisplayName("AI 越界行业叙述丢弃（零新增事实：行业集 = 规则信号集）")
    void generateFor_aiForeignIndustry_dropped() throws Exception {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        llmResponse(
                                """
                                {"summary":"s","watchPoints":[],
                                 "trendNarratives":[{"industry":"煤炭","narrative":"越界行业叙述","confidence":"HIGH"}]}
                                """));

        service.generateFor(WEEK_START);

        ArgumentCaptor<IndustryWeeklyReport> captor =
                ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        JsonNode items =
                JSON.readTree(captor.getValue().getContent()).path("trendJudgement").path("items");
        for (JsonNode item : items) {
            assertThat(item.path("industry").asText()).isNotEqualTo("煤炭");
        }
    }

    @Test
    @DisplayName("定时窗口：生成当周周一锚点周报")
    void runScheduledWindow_generatesCurrentWeekMonday() {
        WeeklyReportService.WindowReport report = service.runScheduledWindow();

        assertThat(report.generated()).isEqualTo(1);
        verify(weeklyRepository).upsert(any(IndustryWeeklyReport.class));
        org.mockito.ArgumentCaptor<IndustryWeeklyReport> captor =
                org.mockito.ArgumentCaptor.forClass(IndustryWeeklyReport.class);
        verify(weeklyRepository).upsert(captor.capture());
        assertThat(captor.getValue().getWeekStart()).isEqualTo(WEEK_START);
    }

    @Test
    @DisplayName("占位符注册（ADR-0022 同源闸门）：周报六键 + briefTypes=9")
    void placeholders_registered() {
        assertThat(service.briefTypes()).containsExactly(BriefType.INDUSTRY_WEEKLY);
        assertThat(service.provided())
                .extracting("key")
                .containsExactly(
                        "weekStart",
                        "weekEnd",
                        "heatStats",
                        "topEvents",
                        "policyLines",
                        "trendSignals");
    }

    @Test
    @DisplayName("非周一/非法日期拒（400 语义）")
    void generateFor_nonMonday_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generateFor("2026-09-22"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("周一");
    }

    private static JsonNode findTrend(JsonNode items, String industry) {
        for (JsonNode item : items) {
            if (industry.equals(item.path("industry").asText())) {
                return item;
            }
        }
        throw new IllegalStateException("缺走向判断行业: " + industry);
    }
}
