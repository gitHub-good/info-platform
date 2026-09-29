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
    private com.info.platform.application.markettop.HitStatsService hitStatsService;

    @BeforeEach
    void setUp() {
        configFacade = mock(MarketTopConfigFacade.class);
        queryService = mock(com.info.platform.application.markettop.MarketTopQueryService.class);
        weightsFacade = mock(com.info.platform.application.valuation.ScoreWeightConfigFacade.class);
        hitStatsService = mock(com.info.platform.application.markettop.HitStatsService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new MarketTopController(
                                        configFacade, queryService, weightsFacade, hitStatsService))
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
                        "A_SHARE",
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
                        new com.info.platform.application.markettop.MarketTopQueryService
                                .LeaderboardView("CNY", "A股：申万一级 31", true, null, null),
                        List.of(),
                        "榜单为多因子信息整理与 AI 摘要，不构成投资建议",
                        null);
        when(queryService.rank(null, null, null)).thenReturn(view);

        mockMvc.perform(get("/api/v1/market-top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rankDate").value("2026-09-22"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.triggerSource").value("DAILY"))
                .andExpect(jsonPath("$.data.batch.degraded").value(false))
                .andExpect(jsonPath("$.data.batch.funnelStats.topSize").value(10))
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.market").value("A_SHARE"))
                .andExpect(jsonPath("$.data.leaderboard.currency").value("CNY"))
                .andExpect(jsonPath("$.data.leaderboard.diveAvailable").value(true));
    }

    @Test
    void rank_noRankingDay_30089() throws Exception {
        when(queryService.rank(null, "2026-09-22", null))
                .thenThrow(new BusinessException(ErrorCode.MARKET_TOP_NOT_FOUND, "该日无榜单数据"));

        mockMvc.perform(get("/api/v1/market-top").param("date", "2026-09-22"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30089));
    }

    @Test
    void rank_invalidParamOrMissingVersion_30090() throws Exception {
        when(queryService.rank(null, "2026/09/22", null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_QUERY_INVALID, "非法日期（需 yyyy-MM-dd）"));

        mockMvc.perform(get("/api/v1/market-top").param("date", "2026/09/22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30090));
    }

    @Test
    void versions_returnsWrappedList() throws Exception {
        when(queryService.versions(null, null))
                .thenReturn(
                        List.of(
                                new com.info.platform.domain.markettop.MarketTopRepository
                                        .VersionSummary(
                                        com.info.platform.domain.aggregation.Market.A_SHARE,
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

    // ---- M29 T256：market 查询参数 ----

    @Test
    void rank_marketParam_passedThroughToQueryService() throws Exception {
        when(queryService.rank("HK", null, null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_NOT_FOUND, "该市场无榜单数据: market=HK"));

        mockMvc.perform(get("/api/v1/market-top").param("market", "HK"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30089));
        org.mockito.Mockito.verify(queryService).rank("HK", null, null);
    }

    @Test
    void rank_invalidMarket_400_30090() throws Exception {
        when(queryService.rank("HK_SZ", null, null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_QUERY_INVALID,
                                "market: 须为 A_SHARE / HK / US，当前值 HK_SZ"));

        mockMvc.perform(get("/api/v1/market-top").param("market", "HK_SZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30090))
                .andExpect(jsonPath("$.msg").value("market: 须为 A_SHARE / HK / US，当前值 HK_SZ"));
    }

    @Test
    void versions_marketParam_passedThrough() throws Exception {
        when(queryService.versions("US", null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/market-top/versions").param("market", "US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        org.mockito.Mockito.verify(queryService).versions("US", null);
    }

    @Test
    void hitStats_marketParam_passedThrough() throws Exception {
        when(hitStatsService.stats("HK"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.MARKET_TOP_NOT_FOUND, "该市场无任何榜单日: market=HK"));

        mockMvc.perform(get("/api/v1/market-top/hit-stats").param("market", "HK"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30089));
        org.mockito.Mockito.verify(hitStatsService).stats("HK");
    }

    // ---- hit-stats（M22 T193，§4.2-③：GET /market-top/hit-stats——数据不足是合法态非错误） ----

    @Test
    void hitStats_returnsWrappedViewWithBasisAndDisclaimer() throws Exception {
        when(hitStatsService.stats(null))
                .thenReturn(
                        new com.info.platform.application.markettop.HitStatsService.HitStatsView(
                                "A_SHARE",
                                "hits-v1:maxVer;price=market_daily_snapshot;win=1/5/20;median=pctChg;sample=priced-only",
                                "2026-10-20",
                                "历史统计不构成收益承诺",
                                List.of(
                                        new com.info.platform.application.markettop.HitStatsService
                                                .WindowView(
                                                "T+1",
                                                List.of(
                                                        new com.info.platform.application.markettop
                                                                .HitStatsService.DayStatView(
                                                                "2026-09-28",
                                                                10,
                                                                9,
                                                                1,
                                                                0.667,
                                                                1.2)),
                                                new com.info.platform.application.markettop
                                                        .HitStatsService.AggView(
                                                        15, "OK", 0.58, 0.9)),
                                        new com.info.platform.application.markettop.HitStatsService
                                                .WindowView(
                                                "T+20",
                                                List.of(),
                                                new com.info.platform.application.markettop
                                                        .HitStatsService.AggView(
                                                        0, "INSUFFICIENT", null, null)))));

        mockMvc.perform(get("/api/v1/market-top/hit-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.asOf").value("2026-10-20"))
                .andExpect(jsonPath("$.data.disclaimer").value("历史统计不构成收益承诺"))
                .andExpect(jsonPath("$.data.windows[0].window").value("T+1"))
                .andExpect(jsonPath("$.data.windows[0].days[0].pricedSamples").value(9))
                .andExpect(jsonPath("$.data.windows[0].days[0].excluded").value(1))
                .andExpect(jsonPath("$.data.windows[0].agg.status").value("OK"))
                .andExpect(jsonPath("$.data.windows[1].window").value("T+20"))
                .andExpect(jsonPath("$.data.windows[1].agg.status").value("INSUFFICIENT"));
    }

    @Test
    void hitStats_noRankDays_404_30089() throws Exception {
        when(hitStatsService.stats(null))
                .thenThrow(new BusinessException(ErrorCode.MARKET_TOP_NOT_FOUND, "无任何榜单日"));

        mockMvc.perform(get("/api/v1/market-top/hit-stats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30089));
    }
}
