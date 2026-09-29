package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ClassificationService 单测（T121，方案 §4.3 / §6 L1 组）：批量渲染契约（batchSize/items JSON 行/companies
 * 提示/truncation）、 cacheable=false 与采样参数断言、解析对齐（对象包裹数组/markdown 围栏/裸数组容错、id 不齐与重复 id 防御、非法枚举与置信度越界）、
 * 对半拆批递归至单条、低置信兜底（raw_main 留痕）、网络类失败本 tick 放弃、幂等（已 DONE 条件更新 0 行不计数）。 LLM 全 mock 零外呼。 AAA 结构。
 */
class ClassificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private NewsAnalysisRepository repository;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private SubjectMatcher subjectMatcher;
    private ClassificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsAnalysisRepository.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        subjectMatcher = mock(SubjectMatcher.class);
        when(subjectMatcher.match(anyString(), anyString())).thenReturn(List.of());
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        PipelineSettings settings = new PipelineSettings(configService, new ObjectMapper());
        service =
                new ClassificationService(
                        repository,
                        llmGateway,
                        promptTemplateService,
                        subjectMatcher,
                        settings,
                        Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                        new ObjectMapper());
        when(promptTemplateService.loadActiveTemplate(BriefType.L1_CLASSIFY))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                1L,
                                BriefType.L1_CLASSIFY,
                                "v1.0",
                                "---SYSTEM---\njson\n---USER---\n{{batchSize}}\n{{items}}",
                                1));
        when(promptTemplateService.render(any(), any()))
                .thenReturn(
                        List.of(new ChatMessage("system", "sys"), new ChatMessage("user", "ctx")));
        when(repository.applyL1Result(any())).thenReturn(1);
    }

    private static NewsAnalysisRepository.ClassificationCandidate item(long id, String title) {
        return new NewsAnalysisRepository.ClassificationCandidate(
                id, title, "摘要-" + title, "新浪财经", NOW.minusSeconds(600), NOW.minusSeconds(300));
    }

    private static String results(String... rows) {
        return "{\"results\":[" + String.join(",", rows) + "]}";
    }

    private static String row(long id, String main, String sub, double confidence) {
        return "{\"id\":"
                + id
                + ",\"main\":\""
                + main
                + "\",\"sub\":"
                + (sub == null ? "null" : "\"" + sub + "\"")
                + ",\"confidence\":"
                + confidence
                + ",\"reason\":\"依据\"}";
    }

    private void stubResponses(String... contents) {
        LlmResponse[] responses = new LlmResponse[contents.length];
        for (int i = 0; i < contents.length; i++) {
            responses[i] = response(contents[i]);
        }
        when(llmGateway.chat(any(LlmRequest.class))).thenReturn(responses[0], tail(responses));
    }

    private static LlmResponse[] tail(LlmResponse[] responses) {
        LlmResponse[] rest = new LlmResponse[responses.length - 1];
        System.arraycopy(responses, 1, rest, 0, rest.length);
        return rest;
    }

    private static LlmResponse response(String content) {
        return new LlmResponse(
                content, new LlmUsage(100, 50), LlmProvider.DEEPSEEK, "deepseek-flash");
    }

    // ---- 渲染与调用契约 ----

    @Test
    void classifyBatch_happyPath_rendersCallsAndPersists() {
        // Arrange
        stubResponses(results(row(1, "银行", null, 0.85), row(2, "食品饮料", "农林牧渔", 0.78)));

        // Act
        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "央行罚款四家银行"), item(2, "贵州茅台业绩预增")));

        // Assert：批结果
        assertThat(outcome.done()).isEqualTo(2);
        assertThat(outcome.failed()).isZero();

        // 调用契约：cacheable=false（ADR-0046 裁决 2 绕缓存）+ scene=5 + 管道采样
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(requestCaptor.capture());
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.cacheable()).isFalse();
        assertThat(request.briefTypeKey()).isEqualTo("5");
        assertThat(request.temperature()).isEqualTo(0.1);
        assertThat(request.maxTokens()).isEqualTo(8192);
        assertThat(request.responseFormatType()).isEqualTo(LlmRequest.JSON_OBJECT);

        // 渲染上下文：batchSize + items JSON 行
        ArgumentCaptor<Map<String, String>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), contextCaptor.capture());
        Map<String, String> context = contextCaptor.getValue();
        assertThat(context.get("batchSize")).isEqualTo("2");
        assertThat(context.get("items")).contains("\"id\":1").contains("\"id\":2").contains("新浪财经");

        // 落库字段
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository, times(2)).applyL1Result(writeCaptor.capture());
        NewsAnalysisRepository.L1Write first = writeCaptor.getAllValues().get(0);
        assertThat(first.mainCategory()).isEqualTo("银行");
        assertThat(first.subIndustry()).isNull();
        assertThat(first.rawMain()).isNull(); // 非兜底路径无改写留痕
        assertThat(first.lowConfidence()).isFalse();
        assertThat(first.promptVersion()).isEqualTo("v1.0");
        assertThat(first.classifiedAt()).isEqualTo(NOW);
        NewsAnalysisRepository.L1Write second = writeCaptor.getAllValues().get(1);
        assertThat(second.mainCategory()).isEqualTo("食品饮料");
        assertThat(second.subIndustry()).isEqualTo("农林牧渔");
    }

    @Test
    void classifyBatch_itemsRendering_truncatesAndCarriesCompaniesHint() {
        // Arrange：超长标题/摘要截断 + 池内公司回联提示
        String longTitle = "这是一条超过六十个字符的超长财经资讯标题需要被截断处理".repeat(3);
        String longSummary = "这是一条超过八十个字符的超长摘要同样需要被截断处理以控制提示词体积".repeat(3);
        NewsAnalysisRepository.ClassificationCandidate longItem =
                new NewsAnalysisRepository.ClassificationCandidate(
                        7, longTitle, longSummary, "金十数据", NOW, NOW);
        when(subjectMatcher.match(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new SubjectMatcher.MatchedSubject(
                                        "SH600519", "贵州茅台", "食品饮料", Market.A_SHARE)));
        stubResponses(results(row(7, "食品饮料", null, 0.9)));

        // Act
        service.classifyBatch(List.of(longItem));

        // Assert：截断 + companies 提示
        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService).render(any(), ctxCaptor.capture());
        Map<String, String> context = ctxCaptor.getValue();
        assertThat(context.get("batchSize")).isEqualTo("1");
        String items = context.get("items");
        assertThat(items).contains("\"id\":7").contains("贵州茅台(食品饮料)").contains("金十数据");
        assertThat(items).doesNotContain(longTitle).doesNotContain(longSummary);

        // matched_subjects 留痕（一致性信号 v1）
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository).applyL1Result(writeCaptor.capture());
        assertThat(writeCaptor.getValue().matchedSubjects())
                .isEqualTo("[{\"code\":\"SH600519\",\"name\":\"贵州茅台\",\"industry\":\"食品饮料\"}]");
    }

    @Test
    void classifyBatch_markdownFenceAndRawArray_tolerated() {
        // Arrange：markdown 围栏包裹 + 裸数组（无对象包裹）均能解析（附录 A 实测无围栏，容错为防御位）
        stubResponses("```json\n" + results(row(1, "银行", null, 0.9)) + "\n```");
        ClassificationService.BatchOutcome fenced =
                service.classifyBatch(List.of(item(1, "央行开展逆回购操作")));

        stubResponses("[" + row(2, "电子", null, 0.88) + "]");
        ClassificationService.BatchOutcome rawArray =
                service.classifyBatch(List.of(item(2, "芯片产能利用率回升")));

        assertThat(fenced.done()).isEqualTo(1);
        assertThat(rawArray.done()).isEqualTo(1);
    }

    // ---- 解析对齐防御 ----

    @Test
    void classifyBatch_lowConfidence_fallsBackToMarketOtherWithRawMain() {
        stubResponses(results(row(1, "社会服务", null, 0.30)));

        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "球迷输球思振东媒体评论")));

        assertThat(outcome.done()).isEqualTo(1);
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository).applyL1Result(writeCaptor.capture());
        NewsAnalysisRepository.L1Write write = writeCaptor.getValue();
        assertThat(write.mainCategory()).isEqualTo("市场·其他");
        assertThat(write.rawMain()).isEqualTo("社会服务"); // 兜底改写留痕（抽检用）
        assertThat(write.lowConfidence()).isTrue();
        assertThat(write.confidence()).isEqualTo(0.30);
    }

    @Test
    void classifyBatch_confidenceExactlyFloor_notFallenBack() {
        stubResponses(results(row(1, "银行", null, 0.45))); // 恰等于 floor：不兜底

        service.classifyBatch(List.of(item(1, "银行间市场流动性充裕")));

        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository).applyL1Result(writeCaptor.capture());
        assertThat(writeCaptor.getValue().mainCategory()).isEqualTo("银行");
        assertThat(writeCaptor.getValue().rawMain()).isNull();
        assertThat(writeCaptor.getValue().lowConfidence()).isFalse();
    }

    @Test
    void classifyBatch_containerSub_nullified() {
        // sub 只允许申万枚举：容器值（宏观）清空为 null，条目仍有效
        stubResponses(results(row(1, "银行", "宏观", 0.9)));

        service.classifyBatch(List.of(item(1, "LPR 报价维持不变")));

        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository).applyL1Result(writeCaptor.capture());
        assertThat(writeCaptor.getValue().subIndustry()).isNull();
    }

    @Test
    void classifyBatch_invalidEnumAndOutOfRangeConfidence_splitThenResolved() {
        // Arrange：首响应两条全无效（非法枚举 + 置信度越界）→ 无进展对半拆批 → 两半各自合法落库
        stubResponses(
                results(
                        row(1, "太空采矿", null, 0.8), // 非法枚举（模板漂移可见信号）
                        row(2, "银行", null, 1.5)), // 置信度越界
                results(row(1, "有色金属", null, 0.7)),
                results(row(2, "银行", null, 0.7)));

        // Act
        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "锂矿价格企稳回升"), item(2, "央行净投放")));

        // Assert
        assertThat(outcome.done()).isEqualTo(2);
        assertThat(outcome.failed()).isZero();
        verify(llmGateway, times(3)).chat(any(LlmRequest.class)); // [2] → [1] + [1]
        verify(repository, never()).markL1Failed(any());
    }

    @Test
    void classifyBatch_duplicateIdRows_brokenAndRetried() {
        // 同 id 两行输出（矛盾不可信）→ 该 id 进重试批（方案 §4.3「重复 id」broken 语义）
        stubResponses(
                results(
                        row(1, "银行", null, 0.9),
                        row(1, "电子", null, 0.9),
                        row(2, "医药生物", null, 0.8)),
                results(row(1, "银行", null, 0.9)));

        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "银行资本充足率达标"), item(2, "创新药出海授权")));

        assertThat(outcome.done()).isEqualTo(2); // id=2 首轮落库，id=1 重试落库
        verify(llmGateway, times(2)).chat(any(LlmRequest.class));
        verify(repository, times(2)).applyL1Result(any());
    }

    @Test
    void classifyBatch_unknownIdRow_ignoredMissingIdRetried() {
        // 响应含批外 id（99）：忽略不落库；缺失的 id=1 经部分进展路径进重试批（id=2 首轮已落库）
        stubResponses(
                results(row(99, "银行", null, 0.9), row(2, "电子", null, 0.8)),
                results(row(1, "银行", null, 0.9)));

        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "银行间债券通扩容"), item(2, "芯片产能爬坡")));

        assertThat(outcome.done()).isEqualTo(2);
        verify(llmGateway, times(2)).chat(any(LlmRequest.class));
    }

    // ---- 拆批递归 ----

    @Test
    void classifyBatch_wholeBatchParseFailure_splitsHalfRecursivelyToSingles() {
        // Arrange：4 条批首轮整体解析失败（垃圾文本）→ 拆 [1,2]+[3,4]；[1,2] 合法落库；[3,4] 再失败 → 拆 [3]+[4]；
        // [3] 单条仍失败 → FAILED（attempts+1）；[4] 合法落库
        stubResponses(
                "这不是合法JSON",
                results(row(1, "银行", null, 0.9), row(2, "电子", null, 0.8)),
                "还是垃圾",
                "单条也失败",
                results(row(4, "电子", null, 0.8)));

        // Act
        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(
                        List.of(
                                item(1, "银行条目一"),
                                item(2, "电子条目二"),
                                item(3, "银行条目三"),
                                item(4, "电子条目四")));

        // Assert：done=3 failed=1；markL1Failed 只发生在单条终败（拆批深度 log2(4)+1=3 层闭环）
        assertThat(outcome.done()).isEqualTo(3);
        assertThat(outcome.failed()).isEqualTo(1);
        verify(llmGateway, times(5)).chat(any(LlmRequest.class));
        verify(repository).markL1Failed(List.of(3L));
    }

    // ---- 网络类失败：本 tick 放弃 ----

    @Test
    void classifyBatch_llmException_marksAttemptsAndAbandonsWithoutRecursion() {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("所有 LLM provider 均失败", List.of("deepseek"), null));

        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "条目一"), item(2, "条目二"), item(3, "条目三")));

        assertThat(outcome.done()).isZero();
        assertThat(outcome.failed()).isEqualTo(3);
        verify(llmGateway, times(1)).chat(any(LlmRequest.class)); // 不在同 tick 连环重试（§4.3）
        verify(repository).markL1Failed(List.of(1L, 2L, 3L)); // attempts++，下 tick 24h 窗口自然重试
        verify(repository, never()).applyL1Result(any());
    }

    @Test
    void classifyBatch_quotaRejectedBusinessException_sameAsNetworkFailure() {
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "预算耗尽"));

        ClassificationService.BatchOutcome outcome = service.classifyBatch(List.of(item(1, "条目")));

        assertThat(outcome.failed()).isEqualTo(1);
        verify(repository).markL1Failed(List.of(1L));
    }

    // ---- 幂等与空批 ----

    @Test
    void classifyBatch_conditionalUpdateMiss_notCountedAsDone() {
        // 已 DONE 行（竞态/重复 tick）：条件 UPDATE 返回 0 → done 计数如实为 0（幂等红线）
        when(repository.applyL1Result(any())).thenReturn(0);
        stubResponses(results(row(1, "银行", null, 0.9)));

        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "银行条目")));

        assertThat(outcome.done()).isZero();
        assertThat(outcome.failed()).isZero();
    }

    @Test
    void classifyBatch_emptyBatch_silent() {
        assertThat(service.classifyBatch(List.of()).done()).isZero();
        assertThat(service.classifyBatch(null).failed()).isZero();
        verify(llmGateway, never()).chat(any());
    }

    // ---- 占位符注册（ADR-0022 同源闸门） ----

    @Test
    void placeholders_registeredForL1Classify() {
        assertThat(service.briefTypes()).containsExactly(BriefType.L1_CLASSIFY);
        assertThat(service.provided())
                .extracting("key")
                .containsExactly("batchSize", "items", "marketLabel", "industryEnums");
    }

    // ---- M29 T253：按市场分组注入枚举 + 港美枚举外值兜底 + l1_market 落库 ----

    @Test
    void classifyBatch_mixedMarket_groupsByMarketAndInjectsEnumsPerMarket() {
        // Arrange：条目 1 关联港股标的（多数票 HK）、条目 2 无标的（A_SHARE 容器面）→ 两组各一次调用；
        // 组执行序 = EnumMap 自然序（A_SHARE 先于 HK），响应按组序对齐
        when(subjectMatcher.match(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new SubjectMatcher.MatchedSubject(
                                        "HK00700", "腾讯控股", "软件服务", Market.HK)),
                        List.of());
        stubResponses(results(row(2, "计算机", null, 0.88)), results(row(1, "软件服务", null, 0.9)));

        // Act
        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "腾讯控股回购股份"), item(2, "算力租赁价格上行")));

        // Assert：两组各渲染一次，枚举集按市场注入
        assertThat(outcome.done()).isEqualTo(2);
        ArgumentCaptor<Map<String, String>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateService, times(2)).render(any(), ctxCaptor.capture());
        Map<String, String> aShareContext = ctxCaptor.getAllValues().get(0);
        assertThat(aShareContext.get("marketLabel")).isEqualTo("A股");
        assertThat(aShareContext.get("industryEnums")).contains("计算机").doesNotContain("UNKNOWN");
        Map<String, String> hkContext = ctxCaptor.getAllValues().get(1);
        assertThat(hkContext.get("marketLabel")).isEqualTo("港股");
        assertThat(hkContext.get("industryEnums"))
                .contains("软件服务")
                .contains("银行")
                .contains("UNKNOWN");
        assertThat(hkContext.get("industryEnums")).doesNotContain("农林牧渔"); // 跨市场口径不混用

        // l1_market 落库（跨市场重名行业消歧键；写序同组序：A_SHARE 先）
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository, times(2)).applyL1Result(writeCaptor.capture());
        assertThat(writeCaptor.getAllValues().get(0).l1Market()).isEqualTo("A_SHARE");
        assertThat(writeCaptor.getAllValues().get(0).mainCategory()).isEqualTo("计算机");
        assertThat(writeCaptor.getAllValues().get(1).l1Market()).isEqualTo("HK");
        assertThat(writeCaptor.getAllValues().get(1).mainCategory()).isEqualTo("软件服务");
    }

    @Test
    void classifyBatch_usMarket_invalidEnumFallsBackToMarketOtherWithoutRetry() {
        // Arrange：美股条目模型输出申万枚举（跨市场混用形态）→ 不进重试批，直接兜底「市场·其他」+ low_confidence 留痕
        when(subjectMatcher.match(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new SubjectMatcher.MatchedSubject(
                                        "USAAPL", "苹果", "电子设备与元件", Market.US)));
        stubResponses(results(row(1, "电子", null, 0.9))); // 申万枚举 ∉ 美股集

        // Act
        ClassificationService.BatchOutcome outcome =
                service.classifyBatch(List.of(item(1, "苹果发布新品")));

        // Assert：一次调用即落库（无拆批重试）+ 兜底留痕
        assertThat(outcome.done()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();
        verify(llmGateway, times(1)).chat(any(LlmRequest.class));
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository).applyL1Result(writeCaptor.capture());
        NewsAnalysisRepository.L1Write write = writeCaptor.getValue();
        assertThat(write.mainCategory()).isEqualTo("市场·其他");
        assertThat(write.rawMain()).isEqualTo("电子");
        assertThat(write.lowConfidence()).isTrue();
        assertThat(write.l1Market()).isEqualTo("US");
    }

    @Test
    void classifyBatch_usMarket_unknownAndContainerEnums_accepted() {
        // UNKNOWN（港美股兜底位）与容器 4（跨市场共用）在港美批均为合法主分类
        when(subjectMatcher.match(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new SubjectMatcher.MatchedSubject(
                                        "USMSFT", "微软", "软件与信息服务", Market.US)));
        stubResponses(results(row(1, "UNKNOWN", null, 0.8)), results(row(2, "国际", null, 0.85)));

        ClassificationService.BatchOutcome first =
                service.classifyBatch(List.of(item(1, "微软云业务扩张")));
        when(subjectMatcher.match(anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new SubjectMatcher.MatchedSubject(
                                        "USMSFT", "微软", "软件与信息服务", Market.US)));
        ClassificationService.BatchOutcome second =
                service.classifyBatch(List.of(item(2, "海外科技巨头动向")));

        assertThat(first.done()).isEqualTo(1);
        assertThat(second.done()).isEqualTo(1);
        ArgumentCaptor<NewsAnalysisRepository.L1Write> writeCaptor =
                ArgumentCaptor.forClass(NewsAnalysisRepository.L1Write.class);
        verify(repository, times(2)).applyL1Result(writeCaptor.capture());
        assertThat(writeCaptor.getAllValues().get(0).mainCategory()).isEqualTo("UNKNOWN");
        assertThat(writeCaptor.getAllValues().get(0).lowConfidence()).isFalse();
        assertThat(writeCaptor.getAllValues().get(1).mainCategory()).isEqualTo("国际");
    }

    @Test
    void marketDerivation_majorityVoteWithFixedTiePriority() {
        // 多数票派生：2 港股 1 美股 → HK；平票按 A_SHARE > HK > US 固定优先序（EnumMap 自然序先到先得，
        // A_SHARE 参与平票时优先——容器面贴近既有口径；无标的 → A_SHARE 容器面）
        List<SubjectMatcher.MatchedSubject> hkMajority =
                List.of(
                        new SubjectMatcher.MatchedSubject("HK00700", "腾讯控股", "软件服务", Market.HK),
                        new SubjectMatcher.MatchedSubject("HK09988", "阿里巴巴", "资讯科技器材", Market.HK),
                        new SubjectMatcher.MatchedSubject("USAAPL", "苹果", "电子设备与元件", Market.US));
        assertThat(ClassificationService.deriveMarket(hkMajority)).isEqualTo(Market.HK);
        List<SubjectMatcher.MatchedSubject> hkUsTie =
                List.of(
                        new SubjectMatcher.MatchedSubject("HK00700", "腾讯控股", "软件服务", Market.HK),
                        new SubjectMatcher.MatchedSubject("USAAPL", "苹果", "电子设备与元件", Market.US));
        assertThat(ClassificationService.deriveMarket(hkUsTie)).isEqualTo(Market.HK);
        List<SubjectMatcher.MatchedSubject> aShareTie =
                List.of(
                        new SubjectMatcher.MatchedSubject(
                                "SH600519", "贵州茅台", "食品饮料", Market.A_SHARE),
                        new SubjectMatcher.MatchedSubject("HK00700", "腾讯控股", "软件服务", Market.HK));
        assertThat(ClassificationService.deriveMarket(aShareTie)).isEqualTo(Market.A_SHARE);
        assertThat(ClassificationService.deriveMarket(List.of())).isEqualTo(Market.A_SHARE);
        assertThat(ClassificationService.deriveMarket(null)).isEqualTo(Market.A_SHARE);
    }

    @Test
    void industryEnums_aShareFrozenToV1Order_hkUsSortedDeterministic() {
        // A 股枚举文本与 V23 v1.0 同序冻结（零回归）；港/美股注入确定性 + 与代码白名单同源
        assertThat(ClassificationService.industryEnumsOf(Market.A_SHARE))
                .isEqualTo(
                        "申万一级行业 31 个："
                                + String.join(
                                        "、", "农林牧渔", "基础化工", "钢铁", "有色金属", "电子", "家用电器", "食品饮料",
                                        "纺织服饰", "轻工制造", "医药生物", "公用事业", "交通运输", "房地产", "商贸零售",
                                        "社会服务", "银行", "非银金融", "综合", "建筑材料", "建筑装饰", "电力设备", "机械设备",
                                        "国防军工", "计算机", "传媒", "通信", "煤炭", "石油石化", "环保", "美容护理",
                                        "汽车"));
        String hk = ClassificationService.industryEnumsOf(Market.HK);
        assertThat(hk).contains("港股行业枚举（31+1 个");
        for (String industry : IndustryCategory.HK_INDUSTRIES) {
            assertThat(hk).contains(industry);
        }
        String us = ClassificationService.industryEnumsOf(Market.US);
        assertThat(us).contains("美股行业枚举（40+1 个");
        for (String industry : IndustryCategory.US_INDUSTRIES) {
            assertThat(us).contains(industry);
        }
        assertThat(ClassificationService.industryEnumsOf(Market.HK))
                .isEqualTo(ClassificationService.industryEnumsOf(Market.HK)); // 确定性（Set→固定序）
        assertThat(ClassificationService.marketLabelOf(Market.HK)).isEqualTo("港股");
        assertThat(ClassificationService.marketLabelOf(Market.US)).isEqualTo("美股");
        assertThat(ClassificationService.marketLabelOf(Market.A_SHARE)).isEqualTo("A股");
    }
}
