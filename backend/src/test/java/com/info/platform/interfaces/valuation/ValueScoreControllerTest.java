package com.info.platform.interfaces.valuation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.valuation.ScoreWeightConfigFacade;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsUpdate;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsView;
import com.info.platform.application.valuation.ValueScoreQueryService;
import com.info.platform.application.valuation.ValueScoreQueryService.CoverageView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ValueScoreController 切片测试（T170 coverage §4.7.2 + T171 value-score §4.7.1 + T172 weights §4.7.3）：
 * 路由与 Result 包装、非法日期 400/30088、无快照 404/30086、权重非法 400/30087、并发冲突 409/30065 映射 （standalone MockMvc +
 * service mock）。
 */
class ValueScoreControllerTest {

    private MockMvc mockMvc;
    private ValueScoreQueryService queryService;
    private ScoreWeightConfigFacade weightFacade;

    @BeforeEach
    void setUp() {
        queryService = mock(ValueScoreQueryService.class);
        weightFacade = mock(ScoreWeightConfigFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new ValueScoreController(queryService, weightFacade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void coverage_wrappedView() throws Exception {
        Map<String, Long> flagCounts = new LinkedHashMap<>();
        flagCounts.put("NO_MARKET_DATA", 32L);
        flagCounts.put("NO_ASSOC_INDUSTRY", 2901L);
        when(queryService.coverage(null))
                .thenReturn(new CoverageView("2026-09-21", 5221, 5221, 0, 100.0, 5189, flagCounts));

        mockMvc.perform(get("/api/v1/value-scores/coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.snapshotDate").value("2026-09-21"))
                .andExpect(jsonPath("$.data.activeSubjects").value(5221))
                .andExpect(jsonPath("$.data.snapshotRows").value(5221))
                .andExpect(jsonPath("$.data.missingCount").value(0))
                .andExpect(jsonPath("$.data.coverageRate").value(100.0))
                .andExpect(jsonPath("$.data.marketDataRows").value(5189))
                .andExpect(jsonPath("$.data.flagCounts.NO_ASSOC_INDUSTRY").value(2901));
    }

    @Test
    void coverage_explicitDateDelegated() throws Exception {
        when(queryService.coverage("2026-09-18"))
                .thenReturn(new CoverageView("2026-09-18", 5221, 5000, 221, 95.8, 4900, Map.of()));

        mockMvc.perform(get("/api/v1/value-scores/coverage").param("date", "2026-09-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshotDate").value("2026-09-18"))
                .andExpect(jsonPath("$.data.missingCount").value(221))
                .andExpect(jsonPath("$.data.coverageRate").value(95.8));
    }

    @Test
    void coverage_invalidDate_rejectedWith30088() throws Exception {
        when(queryService.coverage("2026/09/18"))
                .thenThrow(new BusinessException(ErrorCode.VALUE_SCORE_QUERY_INVALID));

        mockMvc.perform(get("/api/v1/value-scores/coverage").param("date", "2026/09/18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30088));
    }

    @Test
    void valueScore_wrappedViewWithFactorBreakdown() throws Exception {
        when(queryService.valueScore(101L))
                .thenReturn(
                        new ValueScoreQueryService.ScoreView(
                                101L,
                                "2026-09-21",
                                72.4,
                                true,
                                17L,
                                99L,
                                List.of(
                                        new ValueScoreQueryService.FactorView(
                                                "catalyst", "事件催化", 81.2, 0.40, false),
                                        new ValueScoreQueryService.FactorView(
                                                "conduction", "行业传导", 63.0, 0.20, false),
                                        new ValueScoreQueryService.FactorView(
                                                "fundamental", "基本面边际", 88.1, 0.20, false),
                                        new ValueScoreQueryService.FactorView(
                                                "risk", "风险安全", 95.0, 0.20, false),
                                        new ValueScoreQueryService.FactorView(
                                                "valuation", "估值水平", 37.9, 0.00, false)),
                                new ObjectMapper()
                                        .readTree("{\"catalyst\":{\"raw\":4.2,\"entries\":[]}}"),
                                List.of(),
                                "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80",
                                "2026-09-21T09:30:00Z",
                                "评分为多因子信息整理，不构成投资建议"));

        mockMvc.perform(get("/api/v1/subjects/101/value-score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.subjectId").value(101))
                .andExpect(jsonPath("$.data.totalScore").value(72.4))
                .andExpect(jsonPath("$.data.breakthrough").value(true))
                .andExpect(jsonPath("$.data.rank").value(17))
                .andExpect(jsonPath("$.data.percentile").value(99))
                .andExpect(jsonPath("$.data.factors[0].key").value("catalyst"))
                .andExpect(jsonPath("$.data.factors[0].weight").value(0.40))
                .andExpect(jsonPath("$.data.factors[4].key").value("valuation"))
                .andExpect(jsonPath("$.data.factors[4].weight").value(0.00))
                .andExpect(
                        jsonPath("$.data.weightBasis")
                                .value(
                                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80"))
                .andExpect(jsonPath("$.data.detail.catalyst.raw").value(4.2))
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty());
    }

    @Test
    void valueScore_noSnapshot_404With30086() throws Exception {
        when(queryService.valueScore(999L))
                .thenThrow(new BusinessException(ErrorCode.VALUE_SCORE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/subjects/999/value-score"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30086));
    }

    // ---- T172 weights（§4.7.3：任务中心 FACTOR_SNAPSHOT 编辑 Dialog 数据源） ----

    private static WeightsView weightsView() {
        return new WeightsView(
                0.40,
                0.20,
                0.20,
                0.20,
                0.00,
                10,
                30,
                5.0,
                3.0,
                1.5,
                60,
                50,
                80,
                "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80",
                "2026-09-22T01:00:00Z");
    }

    @Test
    void weights_wrappedViewWithParamsBasisAndUpdatedAt() throws Exception {
        when(weightFacade.view()).thenReturn(weightsView());

        mockMvc.perform(get("/api/v1/value-scores/weights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.wCatalyst").value(0.40))
                .andExpect(jsonPath("$.data.wConduction").value(0.20))
                .andExpect(jsonPath("$.data.wValuation").value(0.00))
                .andExpect(jsonPath("$.data.catalystWindowDays").value(10))
                .andExpect(jsonPath("$.data.halfLifeDays").value(5.0))
                .andExpect(jsonPath("$.data.k3Saturation").value(1.5))
                .andExpect(jsonPath("$.data.btRiskMin").value(80))
                .andExpect(
                        jsonPath("$.data.basis")
                                .value(
                                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T01:00:00Z"));
    }

    @Test
    void updateWeights_returnsRefreshedView() throws Exception {
        when(weightFacade.update(any(WeightsUpdate.class))).thenReturn(weightsView());

        mockMvc.perform(
                        patch("/api/v1/value-scores/weights")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"wCatalyst\":0.5,\"wConduction\":0.2,\"wFundamental\":0.1,"
                                                + "\"wRisk\":0.1,\"wValuation\":0.1,\"catalystWindowDays\":10,"
                                                + "\"assocWindowDays\":30,\"halfLifeDays\":5.0,"
                                                + "\"k1Saturation\":3.0,\"k3Saturation\":1.5,"
                                                + "\"btCatalystMin\":60,\"btConductionMin\":50,"
                                                + "\"btRiskMin\":80,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.wCatalyst").value(0.40))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T01:00:00Z"));
    }

    @Test
    void updateWeights_illegalValue_400With30087FieldLevelMessage() throws Exception {
        when(weightFacade.update(any(WeightsUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.VALUATION_CONFIG_INVALID,
                                "wValuation: 须在 0.0 ~ 1.0 范围内; btRiskMin: 须在 0 ~ 100 范围内"));

        mockMvc.perform(
                        patch("/api/v1/value-scores/weights")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"wCatalyst\":0.4,\"wConduction\":0.2,\"wFundamental\":0.2,"
                                                + "\"wRisk\":0.2,\"wValuation\":1.5,\"catalystWindowDays\":10,"
                                                + "\"assocWindowDays\":30,\"halfLifeDays\":5.0,"
                                                + "\"k1Saturation\":3.0,\"k3Saturation\":1.5,"
                                                + "\"btCatalystMin\":60,\"btConductionMin\":50,"
                                                + "\"btRiskMin\":120}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30087))
                .andExpect(
                        jsonPath("$.msg")
                                .value("wValuation: 须在 0.0 ~ 1.0 范围内; btRiskMin: 须在 0 ~ 100 范围内"));
    }

    @Test
    void updateWeights_concurrentConflict_409With30065() throws Exception {
        when(weightFacade.update(any(WeightsUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.CONFIG_CONFLICT, "配置已被并发修改: score.weight，请刷新后重试"));

        mockMvc.perform(
                        patch("/api/v1/value-scores/weights")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"wCatalyst\":0.5,\"wConduction\":0.2,\"wFundamental\":0.1,"
                                                + "\"wRisk\":0.1,\"wValuation\":0.1,\"catalystWindowDays\":10,"
                                                + "\"assocWindowDays\":30,\"halfLifeDays\":5.0,"
                                                + "\"k1Saturation\":3.0,\"k3Saturation\":1.5,"
                                                + "\"btCatalystMin\":60,\"btConductionMin\":50,"
                                                + "\"btRiskMin\":80,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T00:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }
}
