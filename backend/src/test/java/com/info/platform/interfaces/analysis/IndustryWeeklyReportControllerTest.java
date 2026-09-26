package com.info.platform.interfaces.analysis;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.IndustryWeeklyReportDetailView;
import com.info.platform.application.analysis.IndustryWeeklyReportListView;
import com.info.platform.application.analysis.IndustryWeeklyReportService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IndustryWeeklyReportController 切片测试（M17 T145，方案 §4.8）：路由 /api/v1/industry-reports/weekly（与日报路由
 * /{reportDate} 无歧义——字面量优先）、列表/详情/重试三端点、Result 包装、30084/30085 错误映射（standalone MockMvc + service
 * mock）。
 */
class IndustryWeeklyReportControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private MockMvc mockMvc;

    private IndustryWeeklyReportService weeklyService;

    @BeforeEach
    void setUp() {
        weeklyService = mock(IndustryWeeklyReportService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new IndustryWeeklyReportController(weeklyService))
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void list_returnsWeeklyReports() throws Exception {
        when(weeklyService.list(eq(null), eq(null)))
                .thenReturn(
                        new IndustryWeeklyReportListView(
                                List.of(
                                        new IndustryWeeklyReportListView.ItemView(
                                                1L,
                                                "2026-09-21",
                                                "SUCCESS",
                                                "本周银行板块显著升温",
                                                120,
                                                30,
                                                false,
                                                NOW.toString(),
                                                NOW.toString())),
                                null));

        mockMvc.perform(get("/api/v1/industry-reports/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reports[0].weekStart").value("2026-09-21"))
                .andExpect(jsonPath("$.data.reports[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reports[0].summary").isNotEmpty())
                .andExpect(jsonPath("$.data.reports[0].totalNews").value(120))
                .andExpect(jsonPath("$.data.reports[0].totalEvents").value(30))
                .andExpect(jsonPath("$.data.reports[0].narrativeDegraded").value(false));
    }

    @Test
    void detail_returnsFiveBlockContent() throws Exception {
        when(weeklyService.detail("2026-09-21"))
                .thenReturn(
                        new IndustryWeeklyReportDetailView(
                                1L,
                                "2026-09-21",
                                "SUCCESS",
                                new ObjectMapper()
                                        .readTree(
                                                "{\"summary\":\"s\",\"trendJudgement\":{\"basis\":\"trend-v1\"}}"),
                                new ObjectMapper().readTree("[]"),
                                null,
                                "v1.0",
                                "trend-v1|heat-v1",
                                NOW.toString(),
                                NOW.toString()));

        mockMvc.perform(get("/api/v1/industry-reports/weekly/2026-09-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekStart").value("2026-09-21"))
                .andExpect(jsonPath("$.data.content.trendJudgement.basis").value("trend-v1"))
                .andExpect(jsonPath("$.data.basis").isNotEmpty());
    }

    @Test
    void detail_notFound_404_30084() throws Exception {
        when(weeklyService.detail("2026-09-21"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_WEEKLY_REPORT_NOT_FOUND, "该周周报不存在: 2026-09-21"));

        mockMvc.perform(get("/api/v1/industry-reports/weekly/2026-09-21"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30084));
    }

    @Test
    void retry_failedWeekly_accepted202() throws Exception {
        when(weeklyService.retry("2026-09-21"))
                .thenReturn(new IndustryWeeklyReportService.RetryAcceptance(88L));

        mockMvc.perform(post("/api/v1/industry-reports/weekly/2026-09-21/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.executionId").value(88));
    }

    @Test
    void retry_alreadySuccess_409_30085() throws Exception {
        when(weeklyService.retry("2026-09-21"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_WEEKLY_REPORT_ALREADY_SUCCESS,
                                "该周周报已成功生成，无需重试"));

        mockMvc.perform(post("/api/v1/industry-reports/weekly/2026-09-21/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30085));
    }
}
