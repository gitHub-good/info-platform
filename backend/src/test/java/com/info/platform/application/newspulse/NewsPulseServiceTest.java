package com.info.platform.application.newspulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.LlmRequest;
import com.info.platform.domain.ai.LlmResponse;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.newspulse.NewsPulseRepository;
import com.info.platform.domain.newspulse.NewsPulseRepository.PulseRow;
import com.info.platform.domain.newspulse.NewsPulseRepository.WindowItem;
import com.info.platform.domain.newspulse.NewsPulseWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** NewsPulseService 单测（V3.2 M28）：规则统计恒产出 / LLM 分析成功与降级 / 手动刷新守卫 / 空窗短路 / 过期判定。 */
class NewsPulseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");

    private NewsPulseRepository repository;
    private LlmGateway llmGateway;
    private PromptTemplateService promptTemplateService;
    private NewsPulseService service;

    @BeforeEach
    void setUp() {
        repository = mock(NewsPulseRepository.class);
        llmGateway = mock(LlmGateway.class);
        promptTemplateService = mock(PromptTemplateService.class);
        service =
                new NewsPulseService(
                        repository,
                        llmGateway,
                        promptTemplateService,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        // insert 直接回填 id 透传
        when(repository.insert(any(PulseRow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static WindowItem item(long newsId, String category, String subjectsJson) {
        return new WindowItem(newsId, "标题" + newsId, category, 0.9, 5.0, subjectsJson);
    }

    private void stubLlm(String content) {
        when(promptTemplateService.loadActiveTemplate(BriefType.NEWS_PULSE))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                1L,
                                BriefType.NEWS_PULSE,
                                "v1.0",
                                "---SYSTEM---x\n---USER---{{items}}",
                                1));
        when(promptTemplateService.render(any(PromptTemplate.class), any()))
                .thenReturn(List.of(new ChatMessage("user", "ctx")));
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenReturn(new LlmResponse(content, null, null, "test-model"));
    }

    @Test
    void analyze_withNews_llmOk_persistsStatsAndAnalysis() {
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(3L);
        when(repository.countWindowClassified(anyString(), anyString())).thenReturn(2L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150)))
                .thenReturn(
                        List.of(
                                item(1, "电子", "[{\"code\":\"SH688041\",\"name\":\"海光信息\"}]"),
                                item(2, "电子", null),
                                item(3, "汽车", "[{\"code\":\"HK00700\",\"name\":\"腾讯控股\"}]")));
        stubLlm(
                "{\"overview\":{\"A股\":\"a\",\"港股\":\"b\",\"美股\":\"c\"},\"keyEvents\":[],"
                        + "\"hotTracks\":[],\"sentiment\":{\"A股\":\"偏多\",\"港股\":\"中性\",\"美股\":\"中性\"}}");

        PulseRow row = service.analyze(NewsPulseWindow.W1H, false);

        assertThat(row.newsCount()).isEqualTo(3);
        assertThat(row.classifiedCount()).isEqualTo(2); // 全窗口分类口径（与截断列表无关）
        assertThat(row.degraded()).isFalse();
        assertThat(row.model()).isEqualTo("test-model");
        assertThat(row.analysis()).contains("\"overview\"");
        // 市场归集：海光信息→A股 1 条、腾讯控股→港股 1 条、无标的→未关联 1 条
        assertThat(row.marketStats())
                .contains("\"market\":\"A股\"")
                .contains("\"market\":\"港股\"")
                .contains("\"market\":\"未关联\"");
        // 行业分布：电子 2、汽车 1
        assertThat(row.industryStats()).contains("\"industry\":\"电子\",\"count\":2");
    }

    @Test
    void analyze_llmFail_degradesToStatsOnly() {
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(1L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150)))
                .thenReturn(List.of(item(1, "电子", null)));
        when(promptTemplateService.loadActiveTemplate(BriefType.NEWS_PULSE))
                .thenReturn(
                        PromptTemplate.reconstruct(
                                1L,
                                BriefType.NEWS_PULSE,
                                "v1.0",
                                "---SYSTEM---x\n---USER---{{items}}",
                                1));
        when(promptTemplateService.render(any(PromptTemplate.class), any()))
                .thenReturn(List.of(new ChatMessage("user", "ctx")));
        when(llmGateway.chat(any(LlmRequest.class)))
                .thenThrow(new LlmException("budget fused", List.of(), null));

        PulseRow row = service.analyze(NewsPulseWindow.W30M, false);

        assertThat(row.degraded()).isTrue();
        assertThat(row.analysis()).isNull();
        assertThat(row.newsCount()).isEqualTo(1); // 统计段照常
        assertThat(row.degradedReason()).isEqualTo("budget fused");
    }

    @Test
    void analyze_llmGarbageJson_degrades() {
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(1L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150)))
                .thenReturn(List.of(item(1, "电子", null)));
        stubLlm("```json\n{\"unexpected\":true}\n```");

        PulseRow row = service.analyze(NewsPulseWindow.W3H, true);

        assertThat(row.degraded()).isTrue();
        assertThat(row.analysis()).isNull();
        assertThat(row.triggerSource()).isEqualTo("MANUAL");
    }

    @Test
    void analyze_emptyWindow_shortCircuitsWithoutLlm() {
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(0L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150))).thenReturn(List.of());

        PulseRow row = service.analyze(NewsPulseWindow.W6H, false);

        assertThat(row.newsCount()).isZero();
        assertThat(row.degraded()).isTrue();
        assertThat(row.degradedReason()).isEqualTo("NO_NEWS");
        verify(llmGateway, never()).chat(any());
    }

    @Test
    void analyze_manualWithinGuard_throwsParamInvalid() {
        // 最新快照 windowEnd = 08:00 - 2min（5min 守卫内）
        when(repository.findLatest("1h"))
                .thenReturn(
                        Optional.of(
                                new PulseRow(
                                        1L,
                                        "1h",
                                        "x",
                                        NOW.minusSeconds(120).toString(),
                                        5,
                                        5,
                                        "[]",
                                        "[]",
                                        null,
                                        null,
                                        null,
                                        "JOB",
                                        true,
                                        null,
                                        null)));

        assertThatThrownBy(() -> service.analyze(NewsPulseWindow.W1H, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PARAM_INVALID);
        verify(repository, never()).insert(any(PulseRow.class));
    }

    @Test
    void analyze_manualBeyondGuard_proceeds() {
        when(repository.findLatest("1h"))
                .thenReturn(
                        Optional.of(
                                new PulseRow(
                                        1L,
                                        "1h",
                                        "x",
                                        NOW.minusSeconds(600).toString(),
                                        5,
                                        5,
                                        "[]",
                                        "[]",
                                        null,
                                        null,
                                        null,
                                        "JOB",
                                        true,
                                        null,
                                        null)));
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(0L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150))).thenReturn(List.of());

        PulseRow row = service.analyze(NewsPulseWindow.W1H, true);

        assertThat(row.triggerSource()).isEqualTo("MANUAL");
    }

    @Test
    void stale_noRow_true_recentRow_false_oldRow_true() {
        when(repository.findLatest("30m")).thenReturn(Optional.empty());
        assertThat(service.stale(NewsPulseWindow.W30M)).isTrue();

        when(repository.findLatest("30m"))
                .thenReturn(
                        Optional.of(
                                new PulseRow(
                                        1L,
                                        "30m",
                                        "x",
                                        NOW.minusSeconds(60).toString(),
                                        1,
                                        1,
                                        "[]",
                                        "[]",
                                        null,
                                        null,
                                        null,
                                        "JOB",
                                        false,
                                        null,
                                        null)));
        assertThat(service.stale(NewsPulseWindow.W30M)).isFalse(); // 1min < 27min（0.9×30m）

        when(repository.findLatest("30m"))
                .thenReturn(
                        Optional.of(
                                new PulseRow(
                                        1L,
                                        "30m",
                                        "x",
                                        NOW.minusSeconds(1800).toString(),
                                        1,
                                        1,
                                        "[]",
                                        "[]",
                                        null,
                                        null,
                                        null,
                                        "JOB",
                                        false,
                                        null,
                                        null)));
        assertThat(service.stale(NewsPulseWindow.W30M)).isTrue(); // 30min ≥ 27min
    }

    @Test
    void analyze_usSubjectsRollToUsMarket() {
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(1L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150)))
                .thenReturn(
                        List.of(
                                item(
                                        9,
                                        "国际",
                                        "[{\"code\":\"USNVDA\",\"name\":\"英伟达\"},{\"code\":\"SZ000858\",\"name\":\"五粮液\"}]")));
        stubLlm(
                "{\"overview\":{\"A股\":\"a\",\"港股\":\"b\",\"美股\":\"c\"},\"keyEvents\":[],"
                        + "\"hotTracks\":[],\"sentiment\":{\"A股\":\"中性\",\"港股\":\"中性\",\"美股\":\"中性\"}}");

        ArgumentCaptor<PulseRow> captor = ArgumentCaptor.forClass(PulseRow.class);
        service.analyze(NewsPulseWindow.W24H, false);
        verify(repository).insert(captor.capture());

        // US 前缀 → 美股；SZ → A股；单条多市场标的双计（市场归集按标的维度）
        assertThat(captor.getValue().marketStats())
                .contains("\"market\":\"美股\"")
                .contains("\"market\":\"A股\"");
    }

    @Test
    void analyze_marketStats_fourBucketsLightUp_usAndMarketFieldPreferred() {
        // M29 T254：港美股标的入池后四桶自然点亮——US 前缀入美股桶；market 字段优先于前缀（消歧）；
        // 不可识别前缀不再误归「美股」（显式四值化：其余不计桶）；跨市场混合条目计入两个市场
        when(repository.countWindowItems(anyString(), anyString())).thenReturn(5L);
        when(repository.countWindowClassified(anyString(), anyString())).thenReturn(5L);
        when(repository.findWindowItems(anyString(), anyString(), eq(150)))
                .thenReturn(
                        List.of(
                                item(1, "电子", "[{\"code\":\"SH688041\",\"name\":\"海光信息\"}]"),
                                item(2, "软件服务", "[{\"code\":\"HK00700\",\"name\":\"腾讯控股\"}]"),
                                item(3, "互联网与数字媒体", "[{\"code\":\"USAAPL\",\"name\":\"苹果\"}]"),
                                // market 字段优先：前缀 SH 但 market=US → 美股桶（派生留痕时消歧更准）
                                item(
                                        4,
                                        "半导体",
                                        "[{\"code\":\"SH99999\",\"name\":\"某美股映射\",\"market\":\"US\"}]"),
                                // 不可识别前缀（无 market 字段）→ 不计任何市场桶，也不计入未关联（有标的）
                                item(5, "银行", "[{\"code\":\"XX0001\",\"name\":\"未知市场标的\"}]")));
        stubLlm("{\"overview\":{},\"keyEvents\":[],\"hotTracks\":[],\"sentiment\":{}}");

        PulseRow row = service.analyze(NewsPulseWindow.W1H, false);

        assertThat(row.marketStats())
                .contains("\"market\":\"A股\",\"newsCount\":1")
                .contains("\"market\":\"港股\",\"newsCount\":1")
                .contains("\"market\":\"美股\",\"newsCount\":2")
                .contains("\"market\":\"未关联\",\"newsCount\":0"); // 5 条全有标的——未关联如实 0
    }
}
