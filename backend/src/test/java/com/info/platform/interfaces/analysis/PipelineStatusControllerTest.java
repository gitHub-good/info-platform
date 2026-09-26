package com.info.platform.interfaces.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.analysis.PipelineStatusService;
import com.info.platform.application.analysis.PipelineStatusView;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PipelineStatusController 切片测试（T121 基础版 → T125 完整版）：路由 /api/v1/pipeline/status、Result 包装、护栏面字段线格式
 * （level/budget/degradeAt/fuseAt/calibratedPerItemMicros/costBasis + SLA/覆盖率比率，standalone MockMvc +
 * service mock，对齐 FeedDashboardControllerTest 惯例）。
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
    void status_returnsWrappedViewWithGuardFace() throws Exception {
        when(statusService.status())
                .thenReturn(
                        new PipelineStatusView(
                                "NEWS_PIPELINE",
                                GuardLevel.DEGRADED,
                                new PipelineStatusView.TodayView(
                                        620, 8, 12, 600, 5, 2, 90, 10, 0.95, 0.75, 0.1),
                                new PipelineStatusView.LastTickView(
                                        "2026-09-22T09:20:00Z",
                                        "2026-09-22T09:21:00Z",
                                        "l0=pass:3; l1=done:3; skipL2=1(degraded)"),
                                1_350_000L,
                                2_000_000L,
                                1_200_000L,
                                1_800_000L,
                                1_100L,
                                "cost-v1:initial",
                                "coverage-v2"));

        mockMvc.perform(get("/api/v1/pipeline/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobKey").value("NEWS_PIPELINE"))
                .andExpect(jsonPath("$.data.level").value("DEGRADED"))
                .andExpect(jsonPath("$.data.today.l0Pass").value(620))
                .andExpect(jsonPath("$.data.today.l1Done").value(600))
                .andExpect(jsonPath("$.data.today.l2Extracted").value(90))
                .andExpect(jsonPath("$.data.today.l2Deferred").value(10))
                .andExpect(jsonPath("$.data.today.l1RateIn30min").value(0.95))
                .andExpect(jsonPath("$.data.today.l2Coverage").value(0.75))
                .andExpect(jsonPath("$.data.today.noEventRatio").value(0.1))
                .andExpect(jsonPath("$.data.coverageBasis").value("coverage-v2"))
                .andExpect(jsonPath("$.data.todayCostMicros").value(1350000))
                .andExpect(jsonPath("$.data.budgetMicros").value(2000000))
                .andExpect(jsonPath("$.data.degradeAtMicros").value(1200000))
                .andExpect(jsonPath("$.data.fuseAtMicros").value(1800000))
                .andExpect(jsonPath("$.data.calibratedPerItemMicros").value(1100))
                .andExpect(jsonPath("$.data.costBasis").value("cost-v1:initial"))
                .andExpect(
                        jsonPath("$.data.lastTick.detail")
                                .value("l0=pass:3; l1=done:3; skipL2=1(degraded)"));
    }

    @Test
    void status_nullLastTickAndRatios_serializedAsNull() throws Exception {
        when(statusService.status())
                .thenReturn(
                        new PipelineStatusView(
                                "NEWS_PIPELINE",
                                GuardLevel.NORMAL,
                                PipelineStatusView.TodayView.empty(),
                                null,
                                0L,
                                2_000_000L,
                                1_200_000L,
                                1_800_000L,
                                1_100L,
                                "cost-v1:initial",
                                "coverage-v2"));

        mockMvc.perform(get("/api/v1/pipeline/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value("NORMAL"))
                .andExpect(jsonPath("$.data.today.l0Pass").value(0))
                .andExpect(jsonPath("$.data.today.l1RateIn30min").doesNotExist())
                .andExpect(jsonPath("$.data.today.l2Coverage").doesNotExist())
                .andExpect(jsonPath("$.data.today.noEventRatio").doesNotExist())
                .andExpect(jsonPath("$.data.lastTick").doesNotExist());
    }
}
