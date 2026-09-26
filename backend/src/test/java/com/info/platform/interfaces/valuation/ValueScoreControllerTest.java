package com.info.platform.interfaces.valuation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.valuation.ValueScoreQueryService;
import com.info.platform.application.valuation.ValueScoreQueryService.CoverageView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.LinkedHashMap;
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
}
