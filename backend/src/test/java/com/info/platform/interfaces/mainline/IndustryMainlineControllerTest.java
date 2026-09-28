package com.info.platform.interfaces.mainline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.mainline.IndustryMainlineConfigFacade;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigUpdate;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.ConfigView;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.LeaderView;
import com.info.platform.application.mainline.IndustryMainlineConfigFacade.MainlineView;
import com.info.platform.application.mainline.IndustryMainlineQueryService;
import com.info.platform.application.mainline.IndustryMainlineService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IndustryMainlineController 切片测试（M27 T244，方案 §4.5——MarketTopControllerTest 同款）：六端点路由与 Result
 * 包装、30093/30094/ 30095/30096 异常映射、PATCH 并发冲突 409/30065、手动重算端点摘要。
 */
class IndustryMainlineControllerTest {

    private MockMvc mockMvc;

    private IndustryMainlineQueryService queryService;

    private IndustryMainlineConfigFacade configFacade;

    private IndustryMainlineService mainlineService;

    @BeforeEach
    void setUp() {
        queryService = mock(IndustryMainlineQueryService.class);
        configFacade = mock(IndustryMainlineConfigFacade.class);
        mainlineService = mock(IndustryMainlineService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new IndustryMainlineController(
                                        queryService,
                                        configFacade,
                                        mainlineService,
                                        Clock.fixed(
                                                Instant.parse("2026-09-28T10:35:00Z"),
                                                ZoneOffset.UTC)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void heatMap_returnsWrappedView() throws Exception {
        when(queryService.heatMap(null))
                .thenReturn(
                        new IndustryMainlineQueryService.HeatMapView(
                                "2026-09-28",
                                "tencent-rank",
                                "2026-09-28T15:00:02+08:00",
                                false,
                                List.of(
                                        new IndustryMainlineQueryService.IndustryCell(
                                                "食品饮料",
                                                -0.17,
                                                -0.82,
                                                57,
                                                122,
                                                -1.68e8,
                                                3.61e12,
                                                "TENCENT_DIRECT",
                                                null))));

        mockMvc.perform(get("/api/v1/industry-heat-map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.snapshotDate").value("2026-09-28"))
                .andExpect(jsonPath("$.data.source").value("tencent-rank"))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.industries[0].industry").value("食品饮料"))
                .andExpect(jsonPath("$.data.industries[0].pctDay").value(-0.17))
                .andExpect(jsonPath("$.data.industries[0].aggMethod").value("TENCENT_DIRECT"));
    }

    @Test
    void heatMap_emptyLibrary_maps404With30093() throws Exception {
        when(queryService.heatMap(null))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_MARKET_SNAPSHOT_EMPTY, "行业行情快照无任何数据"));

        mockMvc.perform(get("/api/v1/industry-heat-map"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30093));
    }

    @Test
    void mainline_returnsWrappedView() throws Exception {
        when(queryService.mainline(null, null))
                .thenReturn(
                        new IndustryMainlineQueryService.MainlineView(
                                "2026-09-28",
                                1,
                                "DAILY",
                                "2026-09-28",
                                false,
                                null,
                                "mainline-v1:...",
                                "2026-09-28T10:30:00Z",
                                List.of(
                                        new IndustryMainlineQueryService.MainlineItemView(
                                                1,
                                                "电子",
                                                88.5,
                                                null,
                                                5,
                                                1,
                                                "NONE",
                                                null,
                                                "mainline-v1:...",
                                                "2026-09-28T10:30:00Z"))));

        mockMvc.perform(get("/api/v1/industry-mainline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rankDate").value("2026-09-28"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.items[0].industry").value("电子"))
                .andExpect(jsonPath("$.data.items[0].persistentDays").value(5));
    }

    @Test
    void mainline_noRank_maps404With30094() throws Exception {
        when(queryService.mainline(null, null))
                .thenThrow(new BusinessException(ErrorCode.INDUSTRY_MAINLINE_NOT_FOUND, "全库无主线榜单"));

        mockMvc.perform(get("/api/v1/industry-mainline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30094));
    }

    @Test
    void detail_invalidIndustry_maps400With30095() throws Exception {
        when(queryService.detail("半导体概念"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_MAINLINE_QUERY_INVALID, "行业参数非申万 31 枚举"));

        mockMvc.perform(get("/api/v1/industry-mainline/半导体概念/detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30095));
    }

    @Test
    void detail_returnsWrappedView() throws Exception {
        when(queryService.detail("电子"))
                .thenReturn(
                        new IndustryMainlineQueryService.DetailView(
                                "电子",
                                "2026-09-28",
                                "tencent-rank",
                                "2026-09-28T15:00:02+08:00",
                                false,
                                -4.93,
                                -6.51,
                                30,
                                400,
                                -2e9,
                                15.6e12,
                                "TENCENT_DIRECT",
                                null,
                                List.of(),
                                List.of(),
                                null,
                                120));

        mockMvc.perform(get("/api/v1/industry-mainline/电子/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.industry").value("电子"))
                .andExpect(jsonPath("$.data.source").value("tencent-rank"))
                .andExpect(jsonPath("$.data.memberCount").value(120));
    }

    @Test
    void config_returnsBothKeys() throws Exception {
        when(configFacade.view())
                .thenReturn(
                        new ConfigView(
                                new MainlineView(
                                        0.40,
                                        0.35,
                                        0.25,
                                        0.5,
                                        0.5,
                                        0.5,
                                        0.3,
                                        0.2,
                                        5,
                                        2,
                                        5,
                                        10,
                                        13,
                                        "2026-09-28T01:00:00Z"),
                                new LeaderView(0.50, 0.35, 0.15, 7, 3, 0.5, 0.5, null)));

        mockMvc.perform(get("/api/v1/industry-mainline/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainline.wp").value(0.40))
                .andExpect(jsonPath("$.data.mainline.topN").value(5))
                .andExpect(jsonPath("$.data.leader.wa").value(0.50))
                .andExpect(jsonPath("$.data.leader.mentionDays").value(7));
    }

    @Test
    void updateConfig_invalid_maps400With30096() throws Exception {
        when(configFacade.update(any(ConfigUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INDUSTRY_MAINLINE_CONFIG_INVALID,
                                "wp+wh+we: 权重和须为 1±0.001"));

        mockMvc.perform(
                        patch("/api/v1/industry-mainline/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"wp\":0.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30096))
                .andExpect(jsonPath("$.msg").value("wp+wh+we: 权重和须为 1±0.001"));
    }

    @Test
    void updateConfig_conflict_maps409With30065() throws Exception {
        when(configFacade.update(any(ConfigUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT, "配置已被并发修改"));

        mockMvc.perform(
                        patch("/api/v1/industry-mainline/config")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }

    @Test
    void recompute_returnsManualSummary() throws Exception {
        when(mainlineService.compute(any(java.time.LocalDate.class), eq(true)))
                .thenReturn(new IndustryMainlineService.GenerationReport(5, "top=5 version=2"));

        mockMvc.perform(post("/api/v1/industry-mainline/recompute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rankDate").value("2026-09-28"))
                .andExpect(jsonPath("$.data.topSize").value(5))
                .andExpect(jsonPath("$.data.detail").value("top=5 version=2"));
    }
}
