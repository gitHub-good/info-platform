package com.info.platform.interfaces.feed;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.feed.FeedDashboardService;
import com.info.platform.application.feed.FeedDashboardView;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FeedDashboardController 切片测试（T114）：路由 /api/v1/feed-dashboard、Result 包装与三区块线格式（standalone MockMvc
 * + service mock，对齐 InfoSourceControllerTest 惯例）。
 */
class FeedDashboardControllerTest {

    private MockMvc mockMvc;
    private FeedDashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = org.mockito.Mockito.mock(FeedDashboardService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new FeedDashboardController(dashboardService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static FeedDashboardView view() {
        return new FeedDashboardView(
                new FeedDashboardView.GlobalView(
                        57,
                        12,
                        12,
                        1,
                        new FeedDashboardView.LatencyView(
                                180000L,
                                540000L,
                                220L,
                                FeedDashboardView.LATENCY_BASIS,
                                List.of(
                                        "ndrc_policy",
                                        "csrc_news",
                                        "stats_release",
                                        "em_macro_indicators"))),
                List.of(
                        new FeedDashboardView.SourceRowView(
                                2L,
                                "t114_b",
                                "失败源",
                                "快讯",
                                "json_api",
                                5,
                                true,
                                false,
                                false,
                                null,
                                4,
                                3,
                                4,
                                0,
                                100L,
                                "2026-09-22T07:59:00Z",
                                "2026-09-22T07:00:00Z",
                                "2026-09-22T08:10:00Z",
                                null,
                                4,
                                "FeedFetchException: 超时",
                                null,
                                "fail",
                                true),
                        new FeedDashboardView.SourceRowView(
                                1L,
                                "t114_a",
                                "正常源",
                                "国际",
                                "rss",
                                30,
                                true,
                                true,
                                false,
                                null,
                                8,
                                54,
                                0,
                                2,
                                540L,
                                "2026-09-22T07:58:00Z",
                                "2026-09-22T07:58:00Z",
                                "2026-09-22T08:28:00Z",
                                null,
                                0,
                                null,
                                "new=1; dup=0; pages=1; backfill=none",
                                "ok",
                                false)),
                List.of(
                        new FeedDashboardView.FailureView(
                                "t114_b",
                                "失败源",
                                "2026-09-22T07:59:00Z",
                                "FeedFetchException: 超时",
                                "state")));
    }

    @Test
    void dashboard_returnsThreeBlockView() throws Exception {
        when(dashboardService.dashboard()).thenReturn(view());

        mockMvc.perform(get("/api/v1/feed-dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.global.todayNewCount").value(57))
                .andExpect(jsonPath("$.data.global.todayDupCount").value(12))
                .andExpect(jsonPath("$.data.global.activeSourceCount").value(12))
                .andExpect(jsonPath("$.data.global.failedSourceCount").value(1))
                .andExpect(jsonPath("$.data.global.latency.p50Millis").value(180000))
                .andExpect(jsonPath("$.data.global.latency.p90Millis").value(540000))
                .andExpect(jsonPath("$.data.global.latency.sampleCount").value(220))
                .andExpect(
                        jsonPath("$.data.global.latency.basis")
                                .value(FeedDashboardView.LATENCY_BASIS))
                .andExpect(
                        jsonPath("$.data.global.latency.excludedSourceCodes[0]")
                                .value("ndrc_policy"))
                .andExpect(jsonPath("$.data.sources.length()").value(2))
                .andExpect(jsonPath("$.data.sources[0].sourceCode").value("t114_b"))
                .andExpect(jsonPath("$.data.sources[0].runState").value("fail"))
                .andExpect(jsonPath("$.data.sources[0].abnormal").value(true))
                .andExpect(jsonPath("$.data.sources[0].todayNewCount").value(3))
                .andExpect(jsonPath("$.data.sources[0].totalCount").value(100))
                .andExpect(jsonPath("$.data.sources[1].runState").value("ok"))
                .andExpect(jsonPath("$.data.failures.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].sourceCode").value("t114_b"))
                .andExpect(jsonPath("$.data.failures[0].origin").value("state"));
    }

    @Test
    void dashboard_serviceThrows_mappedTo50000ByHandler() throws Exception {
        when(dashboardService.dashboard()).thenThrow(new RuntimeException("聚合失败"));

        mockMvc.perform(get("/api/v1/feed-dashboard"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(50000));
    }
}
