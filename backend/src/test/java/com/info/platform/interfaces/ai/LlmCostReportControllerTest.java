package com.info.platform.interfaces.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.LlmBudgetStatus;
import com.info.platform.application.ai.LlmCostReport;
import com.info.platform.application.ai.LlmCostReportService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * LlmCostReportController 切片测试（T30）：200 缺省/显式窗口 + 非法窗口 400（2001）。
 *
 * <p>对齐 AIBriefControllerTest 模式：{@code standaloneSetup} 独立 MockMvc（不启上下文/不走 JwtAuthFilter）， 应用服务
 * Mockito mock，{@link GlobalExceptionHandler} 作 ControllerAdvice（JWT 保护由生产过滤器承担，此处不重复测）。
 */
class LlmCostReportControllerTest {

    private MockMvc mockMvc;
    private LlmCostReportService costReportService;

    private final LlmCostReport sampleReport =
            new LlmCostReport(
                    "7d",
                    "2026-09-15T04:00:00Z",
                    4L,
                    2L,
                    1L,
                    1L,
                    1L,
                    0.6667,
                    0.25,
                    100L,
                    50L,
                    150L,
                    300L,
                    1000L,
                    0.8,
                    List.of(
                            new LlmCostReport.ProviderCost("deepseek", 2L, 2L, 0L, 150L, 300L),
                            new LlmCostReport.ProviderCost("未发起", 1L, 0L, 0L, 0L, 0L)),
                    List.of(new LlmCostReport.SceneCost("1", 3L, 150L, 300L)),
                    List.of(
                            new LlmCostReport.UserBudget(
                                    1001L, 150L, 1000L, 850L, LlmBudgetStatus.NORMAL)));

    @BeforeEach
    void setUp() {
        costReportService = mock(LlmCostReportService.class);
        LlmCostReportController controller = new LlmCostReportController(costReportService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void report_defaultWindow_returns200WithReport() throws Exception {
        // Arrange：缺省 window，服务端默认 7d
        when(costReportService.report(null)).thenReturn(sampleReport);

        // Act + Assert
        mockMvc.perform(get("/api/v1/llm-cost-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.window").value("7d"))
                .andExpect(jsonPath("$.data.totalCalls").value(4))
                .andExpect(jsonPath("$.data.successRate").value(0.6667))
                .andExpect(jsonPath("$.data.cacheHitRate").value(0.25))
                .andExpect(jsonPath("$.data.costMicros").value(300))
                .andExpect(jsonPath("$.data.providers[0].provider").value("deepseek"))
                .andExpect(jsonPath("$.data.scenes[0].scene").value("1"))
                .andExpect(jsonPath("$.data.topUserBudgets[0].status").value("NORMAL"));
    }

    @Test
    void report_explicitTodayWindow_passesParamThrough() throws Exception {
        // Arrange
        when(costReportService.report("today")).thenReturn(sampleReport);

        // Act + Assert
        mockMvc.perform(get("/api/v1/llm-cost-report").param("window", "today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void report_invalidWindow_returns400WithParamErrorCode() throws Exception {
        // Arrange：非法窗口抛业务异常（2001 → HTTP 400）
        when(costReportService.report("90d"))
                .thenThrow(
                        new BusinessException(ErrorCode.PARAM_INVALID, "window 仅支持 today/7d/30d"));

        // Act + Assert
        mockMvc.perform(get("/api/v1/llm-cost-report").param("window", "90d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }
}
