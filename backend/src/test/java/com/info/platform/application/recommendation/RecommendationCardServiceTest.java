package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationResult;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 推荐卡片生成服务单测（T132，方案 §4.5）：LLM 语言组织（briefType=8/scene "8"/cacheable=false/0.1/512）→
 * FactWhitelistValidator 四类违规矩阵拒走模板（不重试 LLM）→ LLM 超时/失败模板兜底 → 降级态直接模板（不调 LLM）→
 * logic_inputs 快照留档 → INSERT OR IGNORE 幂等落卡。LLM 全 Mock 零外呼。
 */
class RecommendationCardServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private static final long USER_ID = 7L;

    private RecommendationCardRepository cardRepository;

    private LlmGateway llmGateway;

    private PromptTemplateService promptTemplateService;

    private PipelineGuardService guardService;

    private SubjectRepository subjectRepository;

    private RecommendationCardService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cardRepository = mock(RecommendationCardRepository.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        guardService = mock(PipelineGuardService.class);
        subjectRepository = mock(SubjectRepository.class);
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        when(cardRepository.insertIgnore(any(RecommendationCard.class))).thenReturn(1);
        when(subjectRepository.findAllNames())
                .thenReturn(List.of("贵州茅台", "宁德时代", "五粮液", "中芯国际"));
        PromptTemplate template =
                PromptTemplate.reconstruct(
                        1L,
                        BriefType.RECOMMEND_CARD,
                        "v1.0",
                        "---SYSTEM---\nsys {{level}}\n---USER---\nuser {{summary}}",
                        1);
        when(promptTemplateService.loadActiveTemplate(BriefType.RECOMMEND_CARD))
                .thenReturn(template);
        // render 透传：把上下文拼接进 user 消息（便于断言占位符注入）
        when(promptTemplateService.render(any(PromptTemplate.class), any()))
                .thenAnswer(
                        invocation -> {
                            Map<String, String> ctx = invocation.getArgument(1);
                            return List.of(
                                    new ChatMessage("system", "sys"),
                                    new ChatMessage("user", "ctx=" + ctx));
                        });
        service =
                new RecommendationCardService(
                        cardRepository,
                        llmGateway,
                        promptTemplateService,
                        guardService,
                        subjectRepository,
                        objectMapper);
    }

    // ---- 事件与关联结果工厂 ----

    private static EventItem event() {
        // reconstruct 携 id（FEED 消费的事件已落库回填；create 产物 id 为空会被服务 fail-fast 拒绝）
        return EventItem.reconstruct(
                9001L,
                9001L,
                EventType.BUYBACK_CHANGE,
                "贵州茅台公告回购计划，拟回购金额不超过30亿元",
                List.of("食品饮料"),
                Direction.BULLISH,
                Importance.HIGH,
                List.of(new EventItem.KeyFigure("回购金额上限", "30", "亿元")),
                List.of(new EventItem.SubjectRef("SH600519", "贵州茅台", "食品饮料")),
                "拟回购金额不超过30亿元",
                NOW,
                "2026-09-22",
                "v1.0",
                NOW,
                NOW);
    }

    private static AssociationResult association() {
        return new AssociationResult(
                RecLevel.P1,
                List.of("食品饮料"),
                List.of(new RecommendationCard.CardSubject("SH600519", "贵州茅台", "食品饮料", true)),
                "BUYBACK_CHANGE|食品饮料",
                6.0,
                "recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)",
                false,
                null);
    }

    private void mockLlm(String logicChain) {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(
                        new LlmResponse(
                                "{\"logicChain\":\"" + logicChain + "\"}",
                                new LlmUsage(100, 20),
                                LlmProvider.DEEPSEEK,
                                "deepseek-flash"));
    }

    // ---- 主路径：LLM 通过白名单 ----

    @Test
    void generate_llmOutputPassesWhitelist_persistedAsLlm() {
        // Arrange：输出只重述输入事实（标的/行业/数字/方向全在允许集）
        mockLlm("贵州茅台公告回购计划，利好你关注的标的，回购金额上限30亿元。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert：gen_method=LLM 留痕，落卡一次
        assertThat(outcome.inserted()).isTrue();
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.LLM);
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        RecommendationCard card = captor.getValue();
        assertThat(card.getLogicChain()).isEqualTo("贵州茅台公告回购计划，利好你关注的标的，回购金额上限30亿元。");
        assertThat(card.getGenMethod()).isEqualTo(CardGenMethod.LLM);
        assertThat(card.getPromptVersion()).isEqualTo("v1.0");
        assertThat(card.getPushStatus()).isEqualTo(CardPushStatus.PENDING);
    }

    @Test
    void generate_llmRequest_scene8NotCacheableLowTempSmallTokens() {
        // Arrange
        mockLlm("贵州茅台公告回购计划，利好你关注的标的。");

        // Act
        service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert：brief_type=8 → briefTypeKey "8"（scene 留痕与护栏成本口径键）、cacheable=false、0.1/512
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(requestCaptor.capture());
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.briefTypeKey()).isEqualTo("8");
        assertThat(request.cacheable()).isFalse();
        assertThat(request.temperature()).isEqualTo(0.1);
        assertThat(request.maxTokens()).isEqualTo(512);
        assertThat(request.responseFormatType()).isEqualTo(LlmRequest.JSON_OBJECT);
    }

    @Test
    void generate_promptContextInjectsSevenPlaceholders() {
        // Arrange
        mockLlm("贵州茅台公告回购计划，利好你关注的标的。");

        // Act
        service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert：7 占位符全注入（level/eventTypeLabel/directionLabel/summary/industries/subjects/watchSubjects）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> ctxCaptor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(promptTemplateService).render(any(PromptTemplate.class), ctxCaptor.capture());
        Map<String, String> ctx = ctxCaptor.getValue();
        assertThat(ctx)
                .containsKeys(
                        "level",
                        "eventTypeLabel",
                        "directionLabel",
                        "summary",
                        "industries",
                        "subjects",
                        "watchSubjects");
        assertThat(ctx.get("level")).contains("P1");
        assertThat(ctx.get("eventTypeLabel")).isEqualTo("回购·增持·减持");
        assertThat(ctx.get("directionLabel")).isEqualTo("利好");
        assertThat(ctx.get("summary")).contains("贵州茅台");
        assertThat(ctx.get("watchSubjects")).contains("贵州茅台");
    }

    // ---- 白名单四类违规矩阵：拒 → 模板兜底（不重试 LLM）----

    @Test
    void generate_fabricatedSubject_fallsBackToTemplate() {
        // Arrange：虚构标的——LLM 输出夹带池内「宁德时代」（∉ 允许集）
        mockLlm("宁德时代同步披露回购，贵州茅台公告回购计划，利好你关注的标的。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        assertThat(captor.getValue().getGenMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        // 模板文案 = P1 三环节结构
        assertThat(captor.getValue().getLogicChain())
                .startsWith("贵州茅台公告回购计划，拟回购金额不超过30亿元——该事件直接涉及你关注的标的贵州茅台。");
        // 不重试 LLM（裁决 3）
        verify(llmGateway, times(1)).chat(any(LlmRequest.class));
    }

    @Test
    void generate_fabricatedIndustry_fallsBackToTemplate() {
        // Arrange：越界行业——LLM 输出提及「医药生物」（∉ event.affected ∪ 命中集）
        mockLlm("贵州茅台公告回购计划，医药生物板块受提振，利好你关注的标的。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        verify(llmGateway, times(1)).chat(any(LlmRequest.class));
    }

    @Test
    void generate_fabricatedNumber_fallsBackToTemplate() {
        // Arrange：编造数字——LLM 输出夹带「90」（结构化数字集只有 30）
        mockLlm("贵州茅台公告回购计划，回购金额上限90亿元，利好你关注的标的。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        verify(llmGateway, times(1)).chat(any(LlmRequest.class));
    }

    @Test
    void generate_directionReversed_fallsBackToTemplate() {
        // Arrange：方向反转——BULLISH 事件 LLM 输出「利空」
        mockLlm("贵州茅台公告回购计划，短期利空出尽，影响你关注的标的。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        verify(llmGateway, times(1)).chat(any(LlmRequest.class));
    }

    // ---- LLM 失败/超时/解析失败/降级态兜底 ----

    @Test
    void generate_llmFailureOrTimeout_fallsBackToTemplate() {
        // Arrange：LlmException = 全 provider 失败（含 30s 软超时耗尽）→ 模板兜底（时效优先，不重试）
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("timeout after fallback chain", null, null));

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        assertThat(captor.getValue().getLogicChain()).contains("该事件直接涉及你关注的标的贵州茅台");
    }

    @Test
    void generate_unparsableLlmOutput_fallsBackToTemplate() {
        // Arrange：输出非法 JSON（解析失败）
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("not-json", new LlmUsage(1, 1), LlmProvider.DEEPSEEK, "m"));

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
    }

    @Test
    void generate_guardNotNormal_skipsLlmEntirely() {
        // Arrange：DEGRADED/FUSED 降级态直接模板（不调 LLM，成本红线联动）
        when(guardService.currentLevel()).thenReturn(GuardLevel.DEGRADED);

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        verify(llmGateway, never()).chat(any(LlmRequest.class));
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        assertThat(captor.getValue().getPromptVersion()).isNull();
    }

    // ---- logic_inputs 快照 + 幂等 ----

    @Test
    void generate_snapshotLogicInputsForAudit() throws Exception {
        // Arrange
        mockLlm("贵州茅台公告回购计划，利好你关注的标的，回购金额上限30亿元。");

        // Act
        service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert：logic_inputs 快照含结构化事实 + 四类允许集（抽检对账与复现面）
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        JsonNode inputs = objectMapper.readTree(captor.getValue().getLogicInputs());
        assertThat(inputs.get("level").asText()).isEqualTo("P1");
        assertThat(inputs.get("eventType").asText()).isEqualTo("BUYBACK_CHANGE");
        assertThat(inputs.get("summary").asText()).contains("贵州茅台");
        assertThat(inputs.get("title").asText()).isEqualTo("贵州茅台拟回购不超30亿元");
        assertThat(inputs.get("allowedSubjects").toString()).contains("贵州茅台");
        assertThat(inputs.get("allowedIndustries").toString()).contains("食品饮料");
        assertThat(inputs.get("allowedNumbers").toString()).contains("30");
        assertThat(inputs.get("direction").asText()).isEqualTo("BULLISH");
        assertThat(inputs.get("recscore").asDouble()).isEqualTo(6.0);
    }

    @Test
    void generate_duplicateConsumption_reportsNotInserted() {
        // Arrange：同事件同用户已有卡（UNIQUE(user_id,event_id) 冲突 → insertIgnore 返回 0）
        when(cardRepository.insertIgnore(any(RecommendationCard.class))).thenReturn(0);
        mockLlm("贵州茅台公告回购计划，利好你关注的标的。");

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert：重复消费直返不视为错误（幂等，调用方不推送）
        assertThat(outcome.inserted()).isFalse();
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.LLM);
    }

    @Test
    void providedDescriptors_matchSevenRegisteredKeys() {
        // Arrange/Act：PlaceholderProvider 同源闸门（与 buildContext ctx.put 同源同序维护，ADR-0022）
        var descriptors = service.provided();

        // Assert
        assertThat(descriptors).hasSize(7);
        assertThat(descriptors.stream().map(PlaceholderDescriptor::key))
                .containsExactly(
                        "level",
                        "eventTypeLabel",
                        "directionLabel",
                        "summary",
                        "industries",
                        "subjects",
                        "watchSubjects");
        assertThat(service.briefTypes()).isEqualTo(Set.of(BriefType.RECOMMEND_CARD));
    }

    @Test
    void generate_templateLoadFailure_stillFallsBackToTemplate() throws Exception {
        // Arrange：模板缺失（配置异常）→ 兜底可用性优先，卡照常生成（promptVersion 留空）
        when(promptTemplateService.loadActiveTemplate(any(BriefType.class)))
                .thenThrow(
                        new com.info.platform.domain.common.BusinessException(
                                com.info.platform.domain.common.ErrorCode
                                        .PROMPT_TEMPLATE_NOT_FOUND));

        // Act
        RecommendationCardService.GenerationOutcome outcome =
                service.generate(USER_ID, event(), "贵州茅台拟回购不超30亿元", association());

        // Assert
        assertThat(outcome.genMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        verify(llmGateway, never()).chat(any(LlmRequest.class));
        ArgumentCaptor<RecommendationCard> captor =
                ArgumentCaptor.forClass(RecommendationCard.class);
        verify(cardRepository).insertIgnore(captor.capture());
        assertThat(captor.getValue().getPromptVersion()).isNull();
    }
}
