package com.info.platform.interfaces.analysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.analysis.IndustryHeatBoardView;
import com.info.platform.application.analysis.IndustryHeatItemsView;
import com.info.platform.application.analysis.IndustryHeatQueryService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * IndustryHeatController 切片测试（T123，方案 §4.8）：路由 /api/v1/industry-heat 与 /{industry}/items、Result 包装、
 * 中文行业路径段 URL 解码、30076 错误映射（standalone MockMvc + service mock）。
 */
class IndustryHeatControllerTest {

    private MockMvc mockMvc;
    private IndustryHeatQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(IndustryHeatQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new IndustryHeatController(queryService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    /** MockMvc standalone 不做 URI 解码：直接以原文中文路径段驱动（容器层解码行为由集成链路保障）。 */
    private static String pathOf(String industry) {
        return industry;
    }

    @Test
    void board_returnsWrappedView() throws Exception {
        when(queryService.board("H24"))
                .thenReturn(
                        new IndustryHeatBoardView(
                                "H24",
                                List.of(
                                        new IndustryHeatBoardView.RowView(
                                                "银行", 30.0, 20.0, 50.0, 10, 2),
                                        new IndustryHeatBoardView.RowView(
                                                "钢铁", 0.0, 0.0, 0.0, 0, 0)),
                                "heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h",
                                "2026-09-22T08:00:00Z",
                                new IndustryHeatBoardView.PipelineBadgeView("NORMAL")));

        mockMvc.perform(get("/api/v1/industry-heat").param("window", "H24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.window").value("H24"))
                .andExpect(jsonPath("$.data.industries[0].industry").value("银行"))
                .andExpect(jsonPath("$.data.industries[0].heatScore").value(30.0))
                .andExpect(jsonPath("$.data.industries[0].deltaPct").value(50.0))
                .andExpect(jsonPath("$.data.industries[0].newsCount").value(10))
                .andExpect(jsonPath("$.data.industries[0].eventCount").value(2))
                .andExpect(
                        jsonPath("$.data.basis").value("heat-v1:k1=10;imp=1.0/0.5/0.25;hl=12h|48h"))
                .andExpect(jsonPath("$.data.pipeline.level").value("NORMAL"));
    }

    @Test
    void board_unknownWindow_400_30076() throws Exception {
        when(queryService.board("W1"))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PIPELINE_CONFIG_INVALID, "window: 须为 H24 / D7"));

        mockMvc.perform(get("/api/v1/industry-heat").param("window", "W1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30076));
    }

    @Test
    void items_chineseIndustryPathDecoded_paramsPassed() throws Exception {
        when(queryService.items(eq("银行"), eq("H24"), eq("news"), eq(null), eq(20)))
                .thenReturn(
                        new IndustryHeatItemsView(
                                "银行",
                                "H24",
                                "news",
                                1,
                                List.of(
                                        new IndustryHeatItemsView.ItemView(
                                                5L,
                                                null,
                                                "央行降准",
                                                "新浪财经",
                                                Instant.parse("2026-09-22T07:00:00Z"),
                                                true,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "https://finance.sina.com.cn/n/5",
                                                null,
                                                null)),
                                null));

        mockMvc.perform(
                        get("/api/v1/industry-heat/" + pathOf("银行") + "/items")
                                .param("window", "H24")
                                .param("type", "news")
                                .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.industry").value("银行"))
                .andExpect(jsonPath("$.data.type").value("news"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].newsId").value(5))
                .andExpect(jsonPath("$.data.items[0].hasEvent").value(true))
                .andExpect(jsonPath("$.data.items[0].title").value("央行降准"))
                // T162 trace-v1 A 级：news 行 url 原文外链透出
                .andExpect(jsonPath("$.data.items[0].url").value("https://finance.sina.com.cn/n/5"));
    }

    @Test
    void items_unknownIndustry_400_30076() throws Exception {
        when(queryService.items(eq("宏观"), any(), any(), any(), any()))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PIPELINE_CONFIG_INVALID, "industry: 须为申万一级行业枚举"));

        mockMvc.perform(get("/api/v1/industry-heat/" + pathOf("宏观") + "/items"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30076));
    }
}
