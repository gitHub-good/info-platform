package com.info.platform.interfaces.common;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.common.OverviewService;
import com.info.platform.application.common.OverviewService.AnomalyTodayCard;
import com.info.platform.application.common.OverviewService.JobHealthCard;
import com.info.platform.application.common.OverviewService.LlmTodayCard;
import com.info.platform.application.common.OverviewService.OverviewView;
import com.info.platform.application.common.OverviewService.Policy24hCard;
import com.info.platform.application.common.OverviewService.PolicyLatestItem;
import com.info.platform.application.common.OverviewService.SourceHealthItem;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * OverviewController 切片测试（T42，契约 §4.4.4）：五卡片 200 形状 + 卡级 error 字段透传（单卡失败不拖垮整页）。
 *
 * <p>对齐 JobControllerTest 模式：standaloneSetup 独立 MockMvc，应用服务 Mockito mock（JWT 保护由生产过滤器承担，白名单外路径
 * /api/v1/overview 缺 token→401 见 {@code JwtAuthFilterTest#overviewPath_requiresToken}）。
 */
class OverviewControllerTest {

    private MockMvc mockMvc;
    private OverviewService overviewService;

    @BeforeEach
    void setUp() {
        overviewService = mock(OverviewService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new OverviewController(overviewService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void overview_returns200WithFiveCards() throws Exception {
        when(overviewService.view())
                .thenReturn(
                        new OverviewView(
                                new LlmTodayCard(8321, 12_345, 20_000, "OK", null),
                                new AnomalyTodayCard(3, null),
                                new Policy24hCard(
                                        12,
                                        List.of(
                                                new PolicyLatestItem(
                                                        101, "关于印发…的行动方案", "2026-09-21")),
                                        null),
                                new JobHealthCard(412, 2, List.of("PUSH_RETRY"), null),
                                List.of(
                                        new SourceHealthItem(
                                                "QUOTE", "REAL", "OK", "2026-09-22T02:00:00Z", 0)),
                                null));

        mockMvc.perform(get("/api/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.llmToday.tokenUsed").value(8321))
                .andExpect(jsonPath("$.data.llmToday.costMicros").value(12345))
                .andExpect(jsonPath("$.data.llmToday.budgetTokens").value(20000))
                .andExpect(jsonPath("$.data.llmToday.status").value("OK"))
                .andExpect(jsonPath("$.data.llmToday.error").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.anomalyToday.count").value(3))
                .andExpect(jsonPath("$.data.policy24h.count").value(12))
                .andExpect(jsonPath("$.data.policy24h.latest[0].id").value(101))
                .andExpect(jsonPath("$.data.policy24h.latest[0].title").value("关于印发…的行动方案"))
                .andExpect(jsonPath("$.data.policy24h.latest[0].publishedAt").value("2026-09-21"))
                .andExpect(jsonPath("$.data.jobHealth.windowRuns").value(412))
                .andExpect(jsonPath("$.data.jobHealth.windowFailed").value(2))
                .andExpect(jsonPath("$.data.jobHealth.unhealthyJobs[0]").value("PUSH_RETRY"))
                .andExpect(jsonPath("$.data.sourceHealth[0].sourceCode").value("QUOTE"))
                .andExpect(jsonPath("$.data.sourceHealth[0].mode").value("REAL"))
                .andExpect(jsonPath("$.data.sourceHealth[0].lastEventType").value("OK"))
                .andExpect(jsonPath("$.data.sourceHealth[0].errors24h").value(0));
    }

    @Test
    void overview_singleCardError_returns200WithErrorFieldForThatCardOnly() throws Exception {
        when(overviewService.view())
                .thenReturn(
                        new OverviewView(
                                new LlmTodayCard(0, 0, 0, "OK", "取数失败：database is locked"),
                                new AnomalyTodayCard(0, null),
                                new Policy24hCard(0, List.of(), null),
                                new JobHealthCard(0, 0, List.of(), null),
                                List.of(),
                                "取数失败：snapshot absent"));

        mockMvc.perform(get("/api/v1/overview"))
                .andExpect(status().isOk()) // 单卡失败仍是 200，卡级 error 字段区分
                .andExpect(jsonPath("$.data.llmToday.error").value("取数失败：database is locked"))
                .andExpect(jsonPath("$.data.anomalyToday.error").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.sourceHealthError").value("取数失败：snapshot absent"));
    }
}
