package com.info.platform.interfaces.analysis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.analysis.NorthStarService;
import com.info.platform.application.analysis.NorthStarView;
import com.info.platform.application.feed.FeedDashboardView;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * NorthStarController 切片测试（M18 T158）：GET /api/v1/north-star——六指标一端点透出（ns-v1 basis 随响应下发）+
 * 采纳统计取当前用户。standalone MockMvc（不加载 Spring 上下文），NorthStarService Mockito mock，
 * UserContext @BeforeEach 模拟 JwtAuthFilter 写入。
 */
class NorthStarControllerTest {

    private MockMvc mockMvc;
    private NorthStarService northStarService;

    @BeforeEach
    void setUp() {
        northStarService = mock(NorthStarService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new NorthStarController(northStarService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(1L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void northStar_returnsSixMetricsWithBasis() throws Exception {
        when(northStarService.northStar(1L))
                .thenReturn(
                        new NorthStarView(
                                NorthStarView.BASIS,
                                "2026-09-26T08:00:00Z",
                                new NorthStarView.LatencyCard(
                                        198_671L,
                                        588_306L,
                                        384,
                                        NorthStarView.STATUS_MET,
                                        FeedDashboardView.LATENCY_BASIS),
                                new NorthStarView.CoverageCard(
                                        28.0 / 31, 28, 31, 500, NorthStarView.STATUS_MET),
                                new NorthStarView.StableSourcesCard(
                                        31, 31, 7, NorthStarView.STATUS_MET),
                                new NorthStarView.DailyIntakeCard(
                                        2100, 2050.4, NorthStarView.STATUS_MET),
                                new NorthStarView.AdoptRateCard(
                                        null, 0, 0, NorthStarView.STATUS_INSUFFICIENT, "adopt-v1"),
                                new NorthStarView.CostGuardCard(
                                        0.1923,
                                        500_000,
                                        2_600_000,
                                        null,
                                        "cost-v2:m18-30src",
                                        NorthStarView.STATUS_MET),
                                List.of(new NorthStarView.IntakePoint("2026-09-26", 2100))));

        mockMvc.perform(get("/api/v1/north-star"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.basis").value("ns-v1"))
                .andExpect(jsonPath("$.data.latency.p50Millis").value(198671))
                .andExpect(jsonPath("$.data.latency.status").value("MET"))
                .andExpect(jsonPath("$.data.coverage.hitIndustries").value(28))
                .andExpect(jsonPath("$.data.stableSources.stableCount").value(31))
                .andExpect(jsonPath("$.data.dailyIntake.avg7d").value(2050.4))
                .andExpect(jsonPath("$.data.adoptRate.status").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.data.costGuard.costBasis").value("cost-v2:m18-30src"))
                .andExpect(jsonPath("$.data.intakeTrend[0].date").value("2026-09-26"))
                .andExpect(jsonPath("$.data.intakeTrend[0].count").value(2100));
    }
}
