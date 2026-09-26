package com.info.platform.interfaces.valuation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ValueScoreController 切片测试（T170 coverage §4.7.2 + T171 value-score §4.7.1）：路由与 Result 包装、 非法日期
 * 400/30088、无快照 404/30086 映射（standalone MockMvc + service mock）。
 */
class ValueScoreControllerTest {

    private MockMvc mockMvc;
    private ValueScoreQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(ValueScoreQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ValueScoreController(queryService))
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
}
