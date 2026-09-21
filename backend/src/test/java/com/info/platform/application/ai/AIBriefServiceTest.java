package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.ai.AiBrief;
import com.info.platform.domain.ai.AiBriefDoneEvent;
import com.info.platform.domain.ai.AiBriefRepository;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefFact;
import com.info.platform.domain.ai.BriefStatus;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmProvider;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * AIBriefService 单测（T21）：POST 受理（幂等/成本上限/标的校验） + 异步生成编排（CAS/上下文/模板/LLM/解析/幻觉校验/回链/发事件） +
 * 失败兜底（解析失败/LlmException→status=2） + 查询（懒查超时）。AAA 结构。
 *
 * <p>mock
 * 全部外部依赖（LlmGateway/PromptTemplateService/AggregationService/SubjectRepository/AiBriefRepository/
 * HallucinationChecker/BriefContentCodec/LlmCostBudget/ApplicationEventPublisher）；不依赖真实 LLM API
 * key。 直调 {@link AIBriefService#generateBrief} 绕过 @Async 代理（同步执行，便于断言）。
 */
class AIBriefServiceTest {

    private static final long TASK_ID = 1L;
    private static final long USER_ID = 1L;
    private static final long SUBJECT_ID = 100L;

    private AiBriefRepository repository;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private BriefContextBuilder contextBuilder;
    private AggregationService aggregationService;
    private SubjectRepository subjectRepository;
    private HallucinationChecker hallucinationChecker;
    private BriefContentCodec contentCodec;
    private LlmCostBudget costBudget;
    private ApplicationEventPublisher eventPublisher;

    private AIBriefService service;

    private final BriefContent sampleContent =
            new BriefContent(
                    "摘要",
                    List.of(),
                    "利好",
                    "理由",
                    "关注",
                    List.of(new BriefFact("ROE 30%", "roe", 30.0, "FINANCE", "http://f")),
                    "AI 生成，非投资建议");

    @BeforeEach
    void setUp() {
        repository = mock(AiBriefRepository.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        contextBuilder = mock(BriefContextBuilder.class);
        aggregationService = mock(AggregationService.class);
        subjectRepository = mock(SubjectRepository.class);
        hallucinationChecker = mock(HallucinationChecker.class);
        contentCodec = mock(BriefContentCodec.class);
        costBudget = mock(LlmCostBudget.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service =
                new AIBriefService(
                        repository,
                        llmGateway,
                        promptTemplateService,
                        contextBuilder,
                        aggregationService,
                        subjectRepository,
                        hallucinationChecker,
                        contentCodec,
                        costBudget,
                        eventPublisher);
        UserContext.set(new UserContext.Principal(USER_ID, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== createBrief（POST 受理） ====================

    @Test
    void createBrief_normal_returnsTaskIdAndTriggersAsync() {
        // Arrange：标的存在、幂等未命中、save 回填 id
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject()));
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), TASK_ID));

        // Act
        Long taskId = service.createBrief(SUBJECT_ID, BriefType.STOCK);

        // Assert：202 taskId + 触发异步事件
        assertThat(taskId).isEqualTo(TASK_ID);
        verify(costBudget).checkBudget(USER_ID);
        ArgumentCaptor<AiBriefGenerationRequestedEvent> captor =
                ArgumentCaptor.forClass(AiBriefGenerationRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
    }

    @Test
    void createBrief_idempotentHit_returnsExistingTaskId() {
        // Arrange：幂等命中已完成任务（distinct id=2L 区分于新建的 1L）
        AiBrief existing =
                AiBrief.reconstruct(
                        2L,
                        SUBJECT_ID,
                        BriefType.STOCK,
                        "v1.0",
                        "deepseek-flash",
                        "{}",
                        null,
                        150,
                        BriefStatus.DONE,
                        "key",
                        5L,
                        Instant.now(),
                        Instant.now());
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(existing));

        // Act
        Long taskId = service.createBrief(SUBJECT_ID, BriefType.STOCK);

        // Assert：直返上次 taskId（2L），不重建、不触发异步
        assertThat(taskId).isEqualTo(2L);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createBrief_quotaExhausted_throws429() {
        // Arrange：成本上限触发
        doThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "配额用尽"))
                .when(costBudget)
                .checkBudget(anyLong());

        // Act + Assert：fail-fast 429，不创建任务
        assertThatThrownBy(() -> service.createBrief(SUBJECT_ID, BriefType.STOCK))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.AI_QUOTA_EXHAUSTED));
        verify(repository, never()).save(any());
    }

    @Test
    void createBrief_subjectNotFound_throws404() {
        // Arrange：个股型标的不存在
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        // Act + Assert：30001/404
        assertThatThrownBy(() -> service.createBrief(SUBJECT_ID, BriefType.STOCK))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND));
    }

    // ==================== generateBrief（异步生成） ====================

    @Test
    void generateBrief_normal_completesAndPublishesDoneEvent() {
        // Arrange：CAS 领取成功 → 上下文/模板/LLM/解析/校验全绿
        stubClaimSuccess();
        stubContextAndTemplate();
        when(llmGateway.chat(any())).thenReturn(llmResponse());
        when(contentCodec.parse(anyString())).thenReturn(Optional.of(sampleContent));
        when(contentCodec.writeJson(any())).thenReturn("{}");
        when(contentCodec.writeSourceLinks(any())).thenReturn("[]");
        when(hallucinationChecker.check(any(), any()))
                .thenReturn(new HallucinationResult(false, List.of()));

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：status=DONE + 写 model/cost + 发 AiBriefDoneEvent(DONE)
        AiBrief saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.DONE);
        assertThat(saved.getModel()).isEqualTo("deepseek-flash");
        assertThat(saved.getPromptVersion()).isEqualTo("v1.0");
        assertThat(saved.getCostTokens()).isEqualTo(150);
        verifyDoneEvent(BriefStatus.DONE);
    }

    @Test
    void generateBrief_hallucinationNeedVerify_statusThree() {
        // Arrange：幻觉校验有待核实 fact
        stubClaimSuccess();
        stubContextAndTemplate();
        when(llmGateway.chat(any())).thenReturn(llmResponse());
        when(contentCodec.parse(anyString())).thenReturn(Optional.of(sampleContent));
        when(contentCodec.writeJson(any())).thenReturn("{}");
        when(contentCodec.writeSourceLinks(any())).thenReturn("[]");
        when(hallucinationChecker.check(any(), any()))
                .thenReturn(new HallucinationResult(true, List.of()));

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：status=3 待核实（content 仍展示，降级不阻断）
        AiBrief saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.NEED_VERIFY);
        verifyDoneEvent(BriefStatus.NEED_VERIFY);
    }

    @Test
    void generateBrief_parseFailed_statusTwo() {
        // Arrange：LLM 返回空/非法 content，解析失败
        stubClaimSuccess();
        stubContextAndTemplate();
        when(llmGateway.chat(any())).thenReturn(llmResponse());
        when(contentCodec.parse(anyString())).thenReturn(Optional.empty());

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：status=2，不发完整 content
        AiBrief saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.FAILED);
        verifyDoneEvent(BriefStatus.FAILED);
    }

    @Test
    void generateBrief_llmException_statusTwo() {
        // Arrange：所有 provider 失败
        stubClaimSuccess();
        stubContextAndTemplate();
        when(llmGateway.chat(any()))
                .thenThrow(new LlmException("all failed", List.of("deepseek", "glm"), null));

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：兜底置失败
        AiBrief saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.FAILED);
        verifyDoneEvent(BriefStatus.FAILED);
    }

    @Test
    void generateBrief_claimLost_skips() {
        // Arrange：CAS 未命中（被并发领走）
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(brief(BriefStatus.PENDING, 0L)));
        when(repository.claim(TASK_ID, 0L)).thenReturn(Optional.empty());

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：跳过——不生成、不存终态、不发事件
        verify(llmGateway, never()).chat(any());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void generateBrief_alreadyTerminal_skips() {
        // Arrange：任务已终态（重启重入）
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(brief(BriefStatus.DONE, 1L)));

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：不重复领取、不重复生成
        verify(repository, never()).claim(eq(TASK_ID), anyLong());
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void generateBrief_quotaExhaustedDuringChat_statusTwo() {
        // Arrange：POST 预检通过，异步 chat 时配额被并发吃掉
        stubClaimSuccess();
        stubContextAndTemplate();
        when(llmGateway.chat(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "配额用尽"));

        // Act
        service.generateBrief(TASK_ID, USER_ID);

        // Assert：兜底置失败（不抛——异步监听器异常兜底）
        AiBrief saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(BriefStatus.FAILED);
        verifyDoneEvent(BriefStatus.FAILED);
    }

    // ==================== getBrief（查询） ====================

    @Test
    void getBrief_completed_returnsViewWithContent() {
        // Arrange：已完成任务，content/sourceLinks 已写
        AiBrief done =
                AiBrief.reconstruct(
                        TASK_ID,
                        SUBJECT_ID,
                        BriefType.STOCK,
                        "v1.0",
                        "deepseek-flash",
                        "{}",
                        "[\"http://f\"]",
                        150,
                        BriefStatus.DONE,
                        "key",
                        2L,
                        Instant.now().minus(1, ChronoUnit.MINUTES),
                        Instant.now());
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(done));
        when(contentCodec.parse("{}")).thenReturn(Optional.of(sampleContent));
        when(contentCodec.readSourceLinks("[\"http://f\"]")).thenReturn(List.of("http://f"));

        // Act
        AIBriefView view = service.getBrief(TASK_ID);

        // Assert：status=1 + content + sourceLinks + disclaimer
        assertThat(view.status()).isEqualTo(1);
        assertThat(view.content()).isEqualTo(sampleContent);
        assertThat(view.sourceLinks()).containsExactly("http://f");
        assertThat(view.disclaimer()).isEqualTo("AI 生成，非投资建议");
    }

    @Test
    void getBrief_notFound_throws404() {
        // Arrange
        when(repository.findById(999L)).thenReturn(Optional.empty());

        // Act + Assert：30032/404
        assertThatThrownBy(() -> service.getBrief(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.AI_BRIEF_NOT_FOUND));
    }

    @Test
    void getBrief_stalePending_forceFailed() {
        // Arrange：PENDING 超过 30min（懒查兜底）
        AiBrief stale =
                AiBrief.reconstruct(
                        TASK_ID,
                        SUBJECT_ID,
                        BriefType.STOCK,
                        "",
                        "",
                        "",
                        null,
                        null,
                        BriefStatus.PENDING,
                        "key",
                        1L,
                        Instant.now().minus(31, ChronoUnit.MINUTES),
                        Instant.now().minus(31, ChronoUnit.MINUTES));
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(stale));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        AIBriefView view = service.getBrief(TASK_ID);

        // Assert：强制置 FAILED + 持久化 + 视图 status=2
        assertThat(view.status()).isEqualTo(2);
        assertThat(view.content()).isNull();
        verify(repository).save(any());
    }

    @Test
    void getBrief_pendingWithinTimeout_remainsPending() {
        // Arrange：PENDING 未超时（刚受理）
        AiBrief fresh = brief(BriefStatus.PENDING, 0L);
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(fresh));

        // Act
        AIBriefView view = service.getBrief(TASK_ID);

        // Assert：不强制置失败，status=0 处理中
        assertThat(view.status()).isEqualTo(0);
        verify(repository, never()).save(any());
    }

    // ==================== fixtures ====================

    private AiBrief captureSaved() {
        ArgumentCaptor<AiBrief> captor = ArgumentCaptor.forClass(AiBrief.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private void verifyDoneEvent(BriefStatus expected) {
        ArgumentCaptor<AiBriefDoneEvent> captor = ArgumentCaptor.forClass(AiBriefDoneEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(expected);
        assertThat(captor.getValue().getTaskId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
    }

    private void stubClaimSuccess() {
        when(repository.findById(TASK_ID)).thenReturn(Optional.of(brief(BriefStatus.PENDING, 0L)));
        // claim 返回 bumped-version 的同任务实体（version=1）
        when(repository.claim(TASK_ID, 0L))
                .thenReturn(
                        Optional.of(
                                AiBrief.reconstruct(
                                        TASK_ID,
                                        SUBJECT_ID,
                                        BriefType.STOCK,
                                        "",
                                        "",
                                        "",
                                        null,
                                        null,
                                        BriefStatus.PENDING,
                                        "key",
                                        1L,
                                        Instant.now(),
                                        Instant.now())));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject()));
        when(aggregationService.getDetail(eq(SUBJECT_ID), anySet())).thenReturn(detail());
    }

    private void stubContextAndTemplate() {
        when(contextBuilder.build(any(), any())).thenReturn(Map.of());
        PromptTemplate template =
                PromptTemplate.reconstruct(
                        1L,
                        BriefType.STOCK,
                        "v1.0",
                        "---SYSTEM---\njson\n---USER---\n{{subjectName}}",
                        1);
        when(promptTemplateService.loadActiveTemplate(BriefType.STOCK)).thenReturn(template);
        when(promptTemplateService.render(any(), any()))
                .thenReturn(
                        List.of(new ChatMessage("system", "json"), new ChatMessage("user", "ctx")));
    }

    private LlmResponse llmResponse() {
        return new LlmResponse(
                "{}-sample-content-{}",
                new LlmUsage(100, 50),
                LlmProvider.DEEPSEEK,
                "deepseek-flash");
    }

    private static SubjectDetail detail() {
        return new SubjectDetail(
                new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                Map.of("price", 1680.0),
                Map.of("roe", 30.55),
                Map.of("peTtm", 25.0),
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static Subject subject() {
        return Subject.reconstruct(
                SUBJECT_ID,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of(),
                "白酒",
                SubjectStatus.ENABLED,
                0L,
                Instant.now(),
                Instant.now());
    }

    /** 构造一个任务实体（指定状态与 version）。 */
    private static AiBrief brief(BriefStatus status, long version) {
        return AiBrief.reconstruct(
                TASK_ID,
                SUBJECT_ID,
                BriefType.STOCK,
                "",
                "",
                "",
                null,
                null,
                status,
                "key",
                version,
                Instant.now(),
                Instant.now());
    }

    /** 回填 id（createBrief 的 save mock 用）。 */
    private static AiBrief withId(AiBrief brief, long id) {
        return AiBrief.reconstruct(
                id,
                brief.getSubjectId(),
                brief.getBriefType(),
                brief.getPromptVersion(),
                brief.getModel(),
                brief.getContent(),
                brief.getSourceLinks(),
                brief.getCostTokens(),
                brief.getStatus(),
                brief.getIdempotencyKey(),
                brief.getVersion(),
                brief.getCreatedAt(),
                brief.getUpdatedAt());
    }
}
