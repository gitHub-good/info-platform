package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.analysis.PipelineSettings;
import com.info.platform.application.markettop.DeepDiveService.DiveResult;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.markettop.Citation;
import com.info.platform.domain.markettop.DeepDiveInput;
import com.info.platform.domain.markettop.DeepDiveInput.EventFact;
import com.info.platform.domain.markettop.DeepDiveInput.FactorDim;
import com.info.platform.domain.markettop.DeepDiveInput.NewsFact;
import com.info.platform.domain.markettop.DeepDiveInput.SubjectRef;
import com.info.platform.domain.markettop.DeepDiveOutcome.GenMethod;
import com.info.platform.domain.markettop.DeepDiveOutputParser;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DeepDiveService 单测（M21 T182，方案 §4.4 校验链矩阵 + §6 测试要点，LLM 全 Mock 零外呼）： 五步链全通过 → LLM 终态；
 * 虚构引用/违禁词/解析失败/结构缺字段 → 各拒走模板兜底（不重试）；成本触顶拒调零外呼；LLM 异常模板兜底 + llmCallThrew 信号；
 * 模板兜底确定性（幂等——同输入同文案）；占位符注册同源。
 */
@ExtendWith(MockitoExtension.class)
class DeepDiveServiceTest {

    /** 合法 LLM 输出（引用全在白名单：EVENT 101/102 + NEWS 201）。 */
    private static final String VALID_LLM_JSON =
            """
            {"thesis":"订单与行业景气双升","highlights":[
              {"text":"签订重大合同","citations":[{"type":"EVENT","id":101}]},
              {"text":"行业政策利好","citations":[{"type":"NEWS","id":201}]}],
             "risks":[
              {"text":"竞争加剧","citations":[{"type":"EVENT","id":102}]},
              {"text":"估值偏高","citations":[{"type":"NEWS","id":201}]}],
             "dataNotes":[]}
            """;

    @Mock private com.info.platform.domain.ai.LlmGateway llmGateway;

    @Mock private PromptTemplateService promptTemplateService;

    @Mock private PipelineGuardService guardService;

    @Mock private PipelineSettings pipelineSettings;

    @Mock private MarketTopConfigSettings configSettings;

    private DeepDiveService service;

    private final PromptTemplate template =
            PromptTemplate.reconstruct(
                    1L,
                    BriefType.DEEP_DIVE,
                    "v1.0",
                    "---SYSTEM---\n含 json 字样\n---USER---\n{{subject}}",
                    1);

    static DeepDiveInput input() {
        return new DeepDiveInput(
                new SubjectRef("SZ300024", "机器人", "机械设备"),
                List.of(
                        new FactorDim("catalyst", "事件催化", 81.2, 0.40),
                        new FactorDim("conduction", "行业传导", 29.4, 0.20),
                        new FactorDim("fundamental", "基本面边际", 55.1, 0.20),
                        new FactorDim("risk", "风险安全", 70.0, 0.20),
                        new FactorDim("valuation", "估值水平", 50.0, 0.00)),
                58.4,
                99,
                true,
                List.of(
                        new EventFact(101, "签订重大合同", "BULLISH", "HIGH", "2026-09-18", 0.75),
                        new EventFact(102, "监管问询", "BEARISH", "MEDIUM", "2026-09-21", 0.35)),
                List.of(new NewsFact(201, "机器人产业政策出台", "2026-09-20T08:00:00Z", "财联社")),
                List.of(),
                Map.of(),
                10,
                2,
                3);
    }

    /** 测试内解析器（应用切片不依赖 infrastructure 实现防架构环；fence 容错面由 DeepDiveOutputParserImplTest 覆盖）。 */
    private static DeepDiveOutputParser testParser() {
        ObjectMapper mapper = new ObjectMapper();
        return rawContent -> {
            try {
                JsonNode root = mapper.readTree(rawContent);
                return Optional.of(
                        new Parsed(
                                root.path("thesis").asText(null),
                                entries(root.path("highlights")),
                                entries(root.path("risks")),
                                List.of()));
            } catch (Exception e) {
                return Optional.empty();
            }
        };
    }

    private static List<Entry> entries(JsonNode array) {
        List<Entry> entries = new java.util.ArrayList<>();
        if (!array.isArray()) {
            return entries;
        }
        for (JsonNode node : array) {
            List<Citation> citations = new java.util.ArrayList<>();
            for (JsonNode citation : node.path("citations")) {
                citations.add(
                        new Citation(citation.path("type").asText(), citation.path("id").asLong()));
            }
            entries.add(new Entry(node.path("text").asText(null), citations));
        }
        return entries;
    }

    @BeforeEach
    void setUp() {
        service =
                new DeepDiveService(
                        llmGateway,
                        promptTemplateService,
                        testParser(),
                        guardService,
                        pipelineSettings,
                        configSettings);
        lenient()
                .when(promptTemplateService.loadActiveTemplate(BriefType.DEEP_DIVE))
                .thenReturn(template);
        lenient().when(promptTemplateService.render(any(), any())).thenReturn(List.of());
        lenient().when(configSettings.current()).thenReturn(MarketTopConfig.defaults());
        lenient().when(pipelineSettings.dailyBudgetMicros()).thenReturn(2_600_000L);
        lenient().when(guardService.todaySceneCostMicros("10")).thenReturn(0L);
    }

    private void llmReturns(String content) {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(content, null, null, null));
    }

    @Test
    void analyze_validOutput_llmOutcomeWithUnionCitations() {
        llmReturns(VALID_LLM_JSON);

        DiveResult result = service.analyze(input());

        assertThat(result.costCapped()).isFalse();
        assertThat(result.llmCallThrew()).isFalse();
        assertThat(result.outcome().method()).isEqualTo(GenMethod.LLM);
        assertThat(result.outcome().citations())
                .containsExactlyInAnyOrder(
                        new Citation("EVENT", 101),
                        new Citation("EVENT", 102),
                        new Citation("NEWS", 201));
        assertThat(result.promptVersion()).isEqualTo("v1.0");
        assertThat(result.citationDrops()).isZero();
    }

    @Test
    void analyze_fabricatedCitation_rejectedToTemplate() {
        // 虚构 EVENT 999（∉ 白名单 {101,102,201}）且该条目无合法兄弟引用 → 条目剔除 → 跌破下限 → 整体兜底
        String fabricated =
                """
                {"thesis":"论点","highlights":[
                  {"text":"虚构亮点","citations":[{"type":"EVENT","id":999}]},
                  {"text":"行业政策利好","citations":[{"type":"NEWS","id":201}]}],
                 "risks":[
                  {"text":"竞争加剧","citations":[{"type":"EVENT","id":102}]},
                  {"text":"估值偏高","citations":[{"type":"NEWS","id":201}]}]}
                """;
        llmReturns(fabricated);

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
        assertThat(result.outcome().citations()).isEmpty();
        assertThat(result.citationDrops()).isEqualTo(1);
        assertThat(result.promptVersion()).isEqualTo("v1.0");
    }

    @Test
    void analyze_prohibitedPhraseInThesis_rejectedToTemplate() {
        String prohibited = VALID_LLM_JSON.replace("订单与行业景气双升", "基本面强劲建议买入");
        llmReturns(prohibited);

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
        assertThat(result.outcome().thesis()).contains("不构成投资建议");
    }

    @Test
    void analyze_parseFailure_rejectedToTemplate() {
        llmReturns("这不是 JSON {{{");

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
    }

    @Test
    void analyze_structureMissingFields_rejectedToTemplate() {
        // risks 仅 1 条（< 2 下限）——结构校验拒
        String malformed =
                """
                {"thesis":"论点","highlights":[
                  {"text":"亮点一","citations":[{"type":"EVENT","id":101}]},
                  {"text":"亮点二","citations":[{"type":"NEWS","id":201}]}],
                 "risks":[{"text":"唯一风险","citations":[{"type":"EVENT","id":102}]}]}
                """;
        llmReturns(malformed);

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
    }

    @Test
    void analyze_costCapped_noLlmCallAndNullOutcome() {
        // Arrange：当日 scene-10 已用 2,500,000μ¥ + 预估 100,000 > 0.30×2,600,000 = 780,000
        when(guardService.todaySceneCostMicros("10")).thenReturn(2_500_000L);

        DiveResult result = service.analyze(input());

        assertThat(result.costCapped()).isTrue();
        assertThat(result.outcome()).isNull();
        assertThat(result.promptVersion()).isNull();
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void analyze_llmThrows_templateFallbackWithThrewSignal() {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("provider 全失败", List.of(), null));

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
        assertThat(result.llmCallThrew()).isTrue();
    }

    @Test
    void analyze_templateMissing_fallsBackWithoutLlmCall() {
        when(promptTemplateService.loadActiveTemplate(BriefType.DEEP_DIVE))
                .thenThrow(new BusinessException(ErrorCode.PROMPT_TEMPLATE_NOT_FOUND, "未配置"));

        DiveResult result = service.analyze(input());

        assertThat(result.outcome().method()).isEqualTo(GenMethod.TEMPLATE);
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void analyze_templateFallbackDeterministic_idempotentForSameInput() {
        llmReturns("坏 JSON 两次");

        DiveResult first = service.analyze(input());
        DiveResult second = service.analyze(input());

        // 同输入同兜底文案（模板数字全部来自结构化输入——幂等红线）
        assertThat(first.outcome().thesis()).isEqualTo(second.outcome().thesis());
        assertThat(first.outcome().thesis())
                .contains("近10日")
                .contains("2 条关联事件")
                .contains("最高重要度 HIGH")
                .contains("所属行业 机械设备")
                .contains("不构成投资建议");
    }

    @Test
    void analyze_requestUsesPipelineScene10NotCacheable() {
        llmReturns(VALID_LLM_JSON);

        service.analyze(input());

        var captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(captor.capture());
        LlmRequest request = captor.getValue();
        assertThat(request.briefTypeKey()).isEqualTo("10");
        assertThat(request.cacheable()).isFalse();
        assertThat(request.responseFormatType()).isEqualTo(LlmRequest.JSON_OBJECT);
    }

    @Test
    void placeholders_registeredNineKeysSameSourceAsComposer() {
        assertThat(service.briefTypes()).containsExactly(BriefType.DEEP_DIVE);
        assertThat(service.provided())
                .extracting(descriptor -> descriptor.key())
                .containsExactly(
                        "subject",
                        "factors",
                        "totalScore",
                        "percentile",
                        "breakthrough",
                        "topEvents",
                        "relatedNews",
                        "industryNews",
                        "marketSnapshot");
        assertThat(service.provided())
                .extracting(descriptor -> descriptor.key())
                .containsExactlyElementsOf(
                        List.copyOf(
                                com.info.platform.domain.markettop.DeepDivePromptComposer
                                        .placeholders(input())
                                        .keySet()));
    }
}
