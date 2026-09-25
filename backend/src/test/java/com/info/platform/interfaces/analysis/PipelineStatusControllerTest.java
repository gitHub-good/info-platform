package com.info.platform.interfaces.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.analysis.PipelineStatusService;
import com.info.platform.application.analysis.PipelineStatusView;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PipelineStatusController 切片测试（T121 基础版）：路由 /api/v1/pipeline/status、Result 包装、字段线格式 （standalone
 * MockMvc + service mock，对齐 FeedDashboardControllerTest 惯例）。
 */
class PipelineStatusControllerTest {

    private MockMvc mockMvc;
    private PipelineStatusService statusService;

    @BeforeEach
    void setUp() {
        statusService = mock(PipelineStatusService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new PipelineStatusController(statusService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void status_returnsWrappedView() throws Exception {
        when(statusService.status())
                .thenReturn(
                        new PipelineStatusView(
                                "NEWS_PIPELINE",
                                new PipelineStatusView.TodayView(620, 8, 12, 600, 5, 2),
                                new PipelineStatusView.LastTickView(
                                        "2026-09-22T09:20:00Z",
                                        "2026-09-22T09:21:00Z",
                                        "l0=pass:3; l1=done:3"),
                                13675L));

        mockMvc.perform(get("/api/v1/pipeline/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobKey").value("NEWS_PIPELINE"))
                .andExpect(jsonPath("$.data.today.l0Pass").value(620))
                .andExpect(jsonPath("$.data.today.l0Noise").value(8))
                .andExpect(jsonPath("$.data.today.l0NearDup").value(12))
                .andExpect(jsonPath("$.data.today.l1Done").value(600))
                .andExpect(jsonPath("$.data.today.l1Pending").value(5))
                .andExpect(jsonPath("$.data.today.l1Failed").value(2))
                .andExpect(jsonPath("$.data.todayCostMicros").value(13675))
                .andExpect(jsonPath("$.data.lastTick.detail").value("l0=pass:3; l1=done:3"));
    }

    @Test
    void status_nullLastTick_serializedAsNull() throws Exception {
        when(statusService.status())
                .thenReturn(
                        new PipelineStatusView(
                                "NEWS_PIPELINE", PipelineStatusView.TodayView.empty(), null, 0L));

        mockMvc.perform(get("/api/v1/pipeline/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.l0Pass").value(0))
                .andExpect(jsonPath("$.data.lastTick").doesNotExist());
    }
}
