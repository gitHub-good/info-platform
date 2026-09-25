package com.info.platform.interfaces.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.analysis.IndustryReportDetailView;
import com.info.platform.application.analysis.IndustryReportListView;
import com.info.platform.application.analysis.IndustryReportService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IndustryReportController 切片测试（T124，方案 §4.8）：三端点路由与 Result 包装、202 retry 受理、错误码映射（30077 冲突 / 30078
 * 不存在 / 30076 参数非法）（standalone MockMvc + service mock）。
 */
class IndustryReportControllerTest {

    private MockMvc mockMvc;
    private IndustryReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = mock(IndustryReportService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new IndustryReportController(reportService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void list_returnsWrappedView() throws Exception {
        when(reportService.list(null, null))
                .thenReturn(
                        new IndustryReportListView(
                                List.of(
                                        new IndustryReportListView.ItemView(
                                                1L,
                                                "2026-09-22",
                                                "SUCCESS",
                                                "银行活跃",
                                                25,
                                                2,
                                                false,
                                                "2026-09-23T00:00:05Z",
                                                "2026-09-23T00:00:05Z")),
                                1L));

        mockMvc.perform(get("/api/v1/industry-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reports[0].reportDate").value("2026-09-22"))
                .andExpect(jsonPath("$.data.reports[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reports[0].summary").value("银行活跃"))
                .andExpect(jsonPath("$.data.reports[0].totalNews").value(25))
                .andExpect(jsonPath("$.data.nextBeforeId").value(1));
    }

    @Test
    void detail_returnsContentJson() throws Exception {
        when(reportService.detail("2026-09-22"))
                .thenReturn(
                        new IndustryReportDetailView(
                                1L,
                                "2026-09-22",
                                "SUCCESS",
                                new ObjectMapper().readTree("{\"summary\":\"s\",\"totalNews\":25}"),
                                new ObjectMapper().readTree("[{\"industry\":\"银行\"}]"),
                                null,
                                "v1.0",
                                "heat-v1|cost-v1",
                                "2026-09-23T00:00:05Z",
                                "2026-09-23T00:00:05Z"));

        mockMvc.perform(get("/api/v1/industry-reports/2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reportDate").value("2026-09-22"))
                .andExpect(jsonPath("$.data.content.summary").value("s"))
                .andExpect(jsonPath("$.data.heatTop[0].industry").value("银行"))
                .andExpect(jsonPath("$.data.basis").value("heat-v1|cost-v1"));
    }

    @Test
    void detail_notFound_404_30078() throws Exception {
        when(reportService.detail("2026-09-22"))
                .thenThrow(new BusinessException(ErrorCode.INDUSTRY_REPORT_NOT_FOUND, "无该日日报"));

        mockMvc.perform(get("/api/v1/industry-reports/2026-09-22"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30078));
    }

    @Test
    void retry_failedReport_accepted202_withExecutionId() throws Exception {
        when(reportService.retry("2026-09-22"))
                .thenReturn(new IndustryReportService.RetryAcceptance(99L));

        mockMvc.perform(post("/api/v1/industry-reports/2026-09-22/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.executionId").value(99));
    }

    @Test
    void retry_alreadySuccess_409_30077() throws Exception {
        when(reportService.retry("2026-09-22"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_REPORT_ALREADY_SUCCESS, "已成功，无需重试"));

        mockMvc.perform(post("/api/v1/industry-reports/2026-09-22/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30077));
    }

    @Test
    void retry_notFound_404_30078() throws Exception {
        when(reportService.retry("2026-09-22"))
                .thenThrow(new BusinessException(ErrorCode.INDUSTRY_REPORT_NOT_FOUND, "无该日日报"));

        mockMvc.perform(post("/api/v1/industry-reports/2026-09-22/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30078));
    }

    @Test
    void retry_invalidDateFormat_400_30076() throws Exception {
        when(reportService.retry("20260922"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PIPELINE_CONFIG_INVALID, "reportDate: 须为 yyyy-MM-dd"));

        mockMvc.perform(post("/api/v1/industry-reports/20260922/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30076));
    }
}
