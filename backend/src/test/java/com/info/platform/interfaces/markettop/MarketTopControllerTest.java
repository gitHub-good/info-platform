package com.info.platform.interfaces.markettop;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.markettop.MarketTopConfigFacade;
import com.info.platform.application.markettop.MarketTopConfigFacade.ConfigView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MarketTopController 切片测试（M21 T181 配置面，方案 §4.7.3）：路由与 Result 包装、默认视图字段、PATCH 非法 400/30091、并发冲突
 * 409/30065 映射（standalone MockMvc + facade mock，ValueScoreControllerTest 同款）。
 */
class MarketTopControllerTest {

    private MockMvc mockMvc;
    private MarketTopConfigFacade configFacade;
    private com.info.platform.application.markettop.MarketTopQueryService queryService;
    private com.info.platform.application.valuation.ScoreWeightConfigFacade weightsFacade;

    @BeforeEach
    void setUp() {
        configFacade = mock(MarketTopConfigFacade.class);
        queryService = mock(com.info.platform.application.markettop.MarketTopQueryService.class);
        weightsFacade = mock(com.info.platform.application.valuation.ScoreWeightConfigFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new MarketTopController(configFacade, queryService, weightsFacade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void config_returnsWrappedView() throws Exception {
        when(configFacade.view())
                .thenReturn(new ConfigView(300, 40, 0.3, 100000L, 0.8, "2026-09-22T01:00:00Z"));

        mockMvc.perform(get("/api/v1/market-top/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.poolSize").value(300))
                .andExpect(jsonPath("$.data.deepDiveLimit").value(40))
                .andExpect(jsonPath("$.data.deepDiveCostCapRatio").value(0.3))
                .andExpect(jsonPath("$.data.diveCostEstimateMicros").value(100000))
                .andExpect(jsonPath("$.data.memberCoverageFloor").value(0.8))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T01:00:00Z"));
    }

    @Test
    void updateConfig_returnsRefreshedView() throws Exception {
        when(configFacade.update(
                        org.mockito.ArgumentMatchers.any(
                                com.info.platform.application.markettop.MarketTopConfigFacade
                                        .ConfigUpdate.class)))
                .thenReturn(new ConfigView(500, 50, 0.5, 200000L, 0.9, "2026-09-22T02:00:00Z"));

        mockMvc.perform(
                        patch("/api/v1/market-top/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"poolSize\":500,\"deepDiveLimit\":50,"
                                                + "\"deepDiveCostCapRatio\":0.5,"
                                                + "\"diveCostEstimateMicros\":200000,"
                                                + "\"memberCoverageFloor\":0.9,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.poolSize").value(500))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T02:00:00Z"));
    }

    @Test
    void updateConfig_invalidFields_rejectedWith30091() throws Exception {
        // deepDiveLimit=60 越界（蓝图 30~50 硬校验）→ 400/30091 字段级、原值保留
        when(configFacade.update(
                        org.mockito.ArgumentMatchers.any(
                                com.info.platform.application.markettop.MarketTopConfigFacade
                                        .ConfigUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_CONFIG_INVALID,
                                "deepDiveLimit: 须在 30 ~ 50 范围内"));

        mockMvc.perform(
                        patch("/api/v1/market-top/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"poolSize\":300,\"deepDiveLimit\":60,"
                                                + "\"deepDiveCostCapRatio\":0.3,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30091))
                .andExpect(jsonPath("$.msg").value("deepDiveLimit: 须在 30 ~ 50 范围内"));
    }

    @Test
    void updateConfig_concurrentConflict_rejectedWith30065() throws Exception {
        when(configFacade.update(
                        org.mockito.ArgumentMatchers.any(
                                com.info.platform.application.markettop.MarketTopConfigFacade
                                        .ConfigUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT, "配置已被并发修改"));

        mockMvc.perform(
                        patch("/api/v1/market-top/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"poolSize\":300,\"deepDiveLimit\":40,"
                                                + "\"deepDiveCostCapRatio\":0.3,"
                                                + "\"diveCostEstimateMicros\":100000,"
                                                + "\"memberCoverageFloor\":0.8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }

    // ---- T183 读取面：GET /market-top + GET /market-top/versions ----

    @Test
    void rank_returnsWrappedView() throws Exception {
        com.info.platform.application.markettop.MarketTopQueryService.RankView view =
                new com.info.platform.application.markettop.MarketTopQueryService.RankView(
                        "2026-09-22",
                        1,
                        "DAILY",
                        new com.info.platform.application.markettop.MarketTopQueryService.BatchView(
                                "2026-09-22",
                                "2026-09-22T10:03:00Z",
                                false,
                                null,
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree("{\"topSize\":10}"),
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree("[]"),
                                null),
                        List.of(),
                        "榜单为多因子信息整理与 AI 摘要，不构成投资建议",
                        null);
        when(queryService.rank(null, null)).thenReturn(view);

        mockMvc.perform(get("/api/v1/market-top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rankDate").value("2026-09-22"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.triggerSource").value("DAILY"))
                .andExpect(jsonPath("$.data.batch.degraded").value(false))
                .andExpect(jsonPath("$.data.batch.funnelStats.topSize").value(10))
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty());
    }

    @Test
    void rank_noRankingDay_30089() throws Exception {
        when(queryService.rank("2026-09-22", null))
                .thenThrow(new BusinessException(ErrorCode.MARKET_TOP_NOT_FOUND, "该日无榜单数据"));

        mockMvc.perform(get("/api/v1/market-top").param("date", "2026-09-22"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30089));
    }

    @Test
    void rank_invalidParamOrMissingVersion_30090() throws Exception {
        when(queryService.rank("2026/09/22", null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_QUERY_INVALID, "非法日期（需 yyyy-MM-dd）"));

        mockMvc.perform(get("/api/v1/market-top").param("date", "2026/09/22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30090));
    }

    @Test
    void versions_returnsWrappedList() throws Exception {
        when(queryService.versions(null))
                .thenReturn(
                        List.of(
                                new com.info.platform.domain.markettop.MarketTopRepository
                                        .VersionSummary(
                                        "2026-09-22",
                                        1,
                                        "DAILY",
                                        false,
                                        null,
                                        "2026-09-22",
                                        10,
                                        "2026-09-22T10:03:00Z")));

        mockMvc.perform(get("/api/v1/market-top/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].rankDate").value("2026-09-22"))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].topSize").value(10))
                .andExpect(jsonPath("$.data[0].degraded").value(false));
    }

    @Test
    void methodology_aggregatesWeightsAndFunnelConfig() throws Exception {
        // BUG-M21-03 回归：方法论聚合端点 500 → 200 两路同源
        when(configFacade.view())
                .thenReturn(new ConfigView(300, 40, 0.3, 100000L, 0.8, "2026-09-27T00:00:00Z"));
        when(weightsFacade.view())
                .thenReturn(
                        new com.info.platform.application.valuation.ScoreWeightConfigFacade
                                .WeightsView(
                                0.4, 0.2, 0.2, 0.2, 0.0, 30, 30, 5.0, 3.0, 1.5, 60, 50, 80, "vs-v1",
                                null));
        mockMvc.perform(get("/api/v1/market-top/methodology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.weights.wCatalyst").value(0.4))
                .andExpect(jsonPath("$.data.funnel.poolSize").value(300));
    }
}
