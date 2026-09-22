package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.policy.AiTendency;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PolicyTendencyService 单测（T28）：judgeTendency 主路径（利好/利空/中性填 ai_tendency）+ 失败降级（解析失败/LLM
 * 失败/成本上限/bias 无法识别 → 保持未判不阻断）+ 上下文装配（nullable 字段占位）+ 跳过缺 id 条目。AAA 结构。
 *
 * <p>mock 全部外部依赖（LlmGateway/PromptTemplateService/BriefContentCodec/PolicyRepository）；不依赖真实 LLM API
 * key。
 */
class PolicyTendencyServiceTest {

    private static final long POLICY_ID = 101L;

    private PolicyRepository policyRepository;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private BriefContentCodec contentCodec;

    private PolicyTendencyService service;

    @BeforeEach
    void setUp() {
        policyRepository = org.mockito.Mockito.mock(PolicyRepository.class);
        llmGateway = org.mockito.Mockito.mock(LlmGateway.class);
        promptTemplateService = org.mockito.Mockito.mock(PromptTemplateService.class);
        contentCodec = org.mockito.Mockito.mock(BriefContentCodec.class);
        service =
                new PolicyTendencyService(
                        policyRepository, llmGateway, promptTemplateService, contentCodec);
        // 通用桩：模板加载 + 渲染（返回两条消息，内容无关紧要）
        when(promptTemplateService.loadActiveTemplate(BriefType.POLICY))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                1L,
                                BriefType.POLICY,
                                "v1.0",
                                "---SYSTEM---\njson\n---USER---\n",
                                1));
        when(promptTemplateService.render(any(), any()))
                .thenReturn(
                        List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "ctx")));
    }

    @Test
    void judgeTendency_bullishBias_fillsBullish() {
        // Arrange：LLM 返回 bias=利好
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"利好\"}"));
        when(contentCodec.parse("{\"bias\":\"利好\"}")).thenReturn(Optional.of(briefContent("利好")));
        when(policyRepository.updateAiTendency(POLICY_ID, AiTendency.BULLISH)).thenReturn(true);

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：填 1 利好
        assertThat(result).isEqualTo(AiTendency.BULLISH);
        verify(policyRepository).updateAiTendency(POLICY_ID, AiTendency.BULLISH);
    }

    @Test
    void judgeTendency_bearishBias_fillsBearish() {
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"利空\"}"));
        when(contentCodec.parse(any())).thenReturn(Optional.of(briefContent("利空")));

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：填 2 利空
        assertThat(result).isEqualTo(AiTendency.BEARISH);
        verify(policyRepository).updateAiTendency(POLICY_ID, AiTendency.BEARISH);
    }

    @Test
    void judgeTendency_neutralBias_fillsNeutral() {
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"中性\"}"));
        when(contentCodec.parse(any())).thenReturn(Optional.of(briefContent("中性")));

        AiTendency result = service.judgeTendency(policyItem());

        assertThat(result).isEqualTo(AiTendency.NEUTRAL);
        verify(policyRepository).updateAiTendency(POLICY_ID, AiTendency.NEUTRAL);
    }

    @Test
    void judgeTendency_parseEmpty_keepsUnjudgedNoUpdate() {
        // Arrange：LLM 返回空/非法 content，解析失败
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse(""));
        when(contentCodec.parse("")).thenReturn(Optional.empty());

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：保持未判，不落库
        assertThat(result).isEqualTo(AiTendency.UNJUDGED);
        verify(policyRepository, never()).updateAiTendency(anyLong(), any());
    }

    @Test
    void judgeTendency_llmFailure_keepsUnjudgedNoUpdate() {
        // Arrange：所有 provider 失败（LlmException）
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("all failed", List.of("deepseek", "glm"), null));

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：不抛，保持未判
        assertThat(result).isEqualTo(AiTendency.UNJUDGED);
        verify(contentCodec, never()).parse(any());
        verify(policyRepository, never()).updateAiTendency(anyLong(), any());
    }

    @Test
    void judgeTendency_quotaExhausted_keepsUnjudgedNoUpdate() {
        // Arrange：成本上限触发（系统任务虽 userId=0 跳过，但 on-demand 场景仍可能触发，须降级不阻断）
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "配额用尽"));

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：保持未判，不阻断
        assertThat(result).isEqualTo(AiTendency.UNJUDGED);
        verify(policyRepository, never()).updateAiTendency(anyLong(), any());
    }

    @Test
    void judgeTendency_unrecognizableBias_keepsUnjudgedNoUpdate() {
        // Arrange：解析成功但 bias 非「利好/利空/中性」
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"待定\"}"));
        when(contentCodec.parse(any())).thenReturn(Optional.of(briefContent("待定")));

        // Act
        AiTendency result = service.judgeTendency(policyItem());

        // Assert：无法识别 → 未判，不落库
        assertThat(result).isEqualTo(AiTendency.UNJUDGED);
        verify(policyRepository, never()).updateAiTendency(anyLong(), any());
    }

    @Test
    void judgeTendency_nullId_skipsWithoutCallingLlm() {
        // Arrange：条目缺 id（未落库）
        PolicyItem noId =
                PolicyItem.create(
                        "无 id 政策", "国务院政策", LocalDate.of(2026, 9, 20), null, List.of(), null);

        // Act
        AiTendency result = service.judgeTendency(noId);

        // Assert：跳过，不调 LLM/模板/落库
        assertThat(result).isEqualTo(AiTendency.UNJUDGED);
        verify(llmGateway, never()).chat(any());
        verify(promptTemplateService, never()).loadActiveTemplate(any());
        verify(policyRepository, never()).updateAiTendency(anyLong(), any());
    }

    @Test
    void judgeTendency_contextAssemblesPlaceholdersForNullableFields() {
        // Arrange：summary/sourceUrl 为 null，relatedIndustries 含多行业
        PolicyItem item =
                PolicyItem.reconstruct(
                        POLICY_ID,
                        "国务院关于白酒与银行的意见",
                        "国务院政策",
                        LocalDate.of(2026, 9, 20),
                        null,
                        List.of("白酒", "银行"),
                        AiTendency.UNJUDGED,
                        null,
                        null,
                        null);
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"利好\"}"));
        when(contentCodec.parse(any())).thenReturn(Optional.of(briefContent("利好")));
        when(policyRepository.updateAiTendency(eq(POLICY_ID), any())).thenReturn(true);

        // Act
        service.judgeTendency(item);

        // Assert：context 占位符装配——nullable 字段置占位、行业逗号连接、watchlistSubjects 占位
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), ctxCaptor.capture());
        Map<String, String> ctx = ctxCaptor.getValue();
        assertThat(ctx.get("policyTitle")).isEqualTo("国务院关于白酒与银行的意见");
        assertThat(ctx.get("publishedAt")).isEqualTo("2026-09-20");
        assertThat(ctx.get("source")).isEqualTo("国务院政策");
        assertThat(ctx.get("policySummary")).isEqualTo("（暂无摘要）");
        assertThat(ctx.get("policyUrl")).isEqualTo("（无链接）");
        assertThat(ctx.get("relatedIndustries")).isEqualTo("白酒、银行");
        assertThat(ctx.get("watchlistSubjects"))
                .isEqualTo(PolicyTendencyService.NO_WATCHLIST_SUBJECTS);
    }

    @Test
    void judgeTendency_updateAffectsZeroRows_logsButReturnsTendency() {
        // Arrange：落库未命中（条目已删）——倾向仍返判断值，仅记 WARN
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(llmResponse("{\"bias\":\"利好\"}"));
        when(contentCodec.parse(any())).thenReturn(Optional.of(briefContent("利好")));
        when(policyRepository.updateAiTendency(POLICY_ID, AiTendency.BULLISH)).thenReturn(false);

        // Act + Assert：返回判断值（不因落库未命中而置未判——判断本身已成功）
        assertThat(service.judgeTendency(policyItem())).isEqualTo(AiTendency.BULLISH);
    }

    private static PolicyItem policyItem() {
        return PolicyItem.reconstruct(
                POLICY_ID,
                "国务院减税政策",
                "国务院政策",
                LocalDate.of(2026, 9, 20),
                "减税降费",
                List.of("新能源"),
                AiTendency.UNJUDGED,
                "https://gov/p/1",
                null,
                null);
    }

    private static BriefContent briefContent(String bias) {
        return new BriefContent(
                "摘要", List.of(), bias, "理由", "关注", List.of(), "AI 生成，非投资建议", List.of());
    }

    private static LlmResponse llmResponse(String content) {
        return new LlmResponse(
                content, new LlmUsage(10, 20), LlmProvider.DEEPSEEK, "deepseek-flash");
    }
}
