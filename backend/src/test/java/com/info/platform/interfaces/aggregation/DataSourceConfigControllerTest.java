package com.info.platform.interfaces.aggregation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.aggregation.DataSourceConfigFacade;
import com.info.platform.application.aggregation.DataSourceConfigFacade.AggregationView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.ConnectivityResult;
import com.info.platform.application.aggregation.DataSourceConfigFacade.DataSourceConfigUpdate;
import com.info.platform.application.aggregation.DataSourceConfigFacade.DataSourceConfigView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.HealthView;
import com.info.platform.application.aggregation.DataSourceConfigFacade.SourceCardView;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DataSourceConfigController 切片测试（T36）：契约形状（GET 7 源卡片 + 健康空态 + effectiveModes）+ 写接口透传（合并请求体） +
 * 业务错误码映射（30061→404 / 30065→409 / 2001→400）。
 *
 * <p>对齐 LlmConfigControllerTest 模式：standaloneSetup 独立 MockMvc，应用端口 Mockito mock，{@link
 * GlobalExceptionHandler} 作 ControllerAdvice（JWT 保护由生产过滤器承担，此处不重复测）。
 */
class DataSourceConfigControllerTest {

    private MockMvc mockMvc;
    private DataSourceConfigFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(DataSourceConfigFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new DataSourceConfigController(facade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static SourceCardView quoteCard() {
        return new SourceCardView(
                "QUOTE",
                "行情源",
                true,
                "REAL",
                1500,
                0,
                5,
                30,
                Map.of("quoteUrl", "https://push2.eastmoney.com/api/qt/stock/get"),
                new HealthView("OK", "2026-09-22T02:00:00Z", 1),
                "2026-09-22T01:00:00Z",
                Map.of("mode", "LIVE", "params", "LIVE"));
    }

    @Test
    void view_returns200WithSevenCardsAndHealth() throws Exception {
        when(facade.view())
                .thenReturn(
                        new DataSourceConfigView(
                                List.of(quoteCard()),
                                new AggregationView(
                                        2000, null, Map.of("detailTimeoutMillis", "LIVE"))));

        mockMvc.perform(get("/api/v1/datasource-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sources[0].sourceCode").value("QUOTE"))
                .andExpect(jsonPath("$.data.sources[0].label").value("行情源"))
                .andExpect(jsonPath("$.data.sources[0].mode").value("REAL"))
                .andExpect(jsonPath("$.data.sources[0].timeoutMillis").value(1500))
                .andExpect(jsonPath("$.data.sources[0].params.quoteUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.sources[0].health.lastEventType").value("OK"))
                .andExpect(jsonPath("$.data.sources[0].health.errors24h").value(1))
                .andExpect(jsonPath("$.data.sources[0].effectiveModes.mode").value("LIVE"))
                .andExpect(jsonPath("$.data.aggregation.detailTimeoutMillis").value(2000));
    }

    @Test
    void update_patchesMergeBodyAndReturnsCard() throws Exception {
        when(facade.update(eq(SourceCode.QUOTE), any(DataSourceConfigUpdate.class)))
                .thenReturn(quoteCard());

        mockMvc.perform(
                        patch("/api/v1/datasource-configs/QUOTE")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"mode\":\"MOCK\",\"timeoutMillis\":3000,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceCode").value("QUOTE"));
    }

    @Test
    void updateAggregation_returnsUpdatedView() throws Exception {
        when(facade.updateAggregation(any()))
                .thenReturn(new AggregationView(3000, "2026-09-22T01:00:01Z", Map.of()));

        mockMvc.perform(
                        patch("/api/v1/datasource-configs/aggregation/global")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"detailTimeoutMillis\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detailTimeoutMillis").value(3000));
    }

    @Test
    void getAggregationGlobal_methodNotAllowed_maps405Not500() throws Exception {
        // ISSUE-C：/aggregation/global 仅注册 PATCH，GET 打来应 405/2002——
        // 此前 HttpRequestMethodNotSupportedException 被兜底 Exception 处理器吞成 500/50000
        mockMvc.perform(get("/api/v1/datasource-configs/aggregation/global"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(2002))
                .andExpect(jsonPath("$.msg", containsString("GET")))
                .andExpect(jsonPath("$.msg", containsString("PATCH")));
    }

    @Test
    void connectivityTest_returnsResultEvenWhenNotOk() throws Exception {
        when(facade.connectivityTest(SourceCode.QUOTE))
                .thenReturn(new ConnectivityResult(false, 1500L, null, "REAL", "抓取未返回有效数据", null));

        mockMvc.perform(post("/api/v1/datasource-configs/QUOTE/connectivity-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.latencyMillis").value(1500))
                .andExpect(jsonPath("$.data.error").isNotEmpty());
    }

    @Test
    void unknownSource_maps404() throws Exception {
        // 控制器层 parse 即拦截未知源（30061 → 404），未到 facade
        mockMvc.perform(post("/api/v1/datasource-configs/NOT_A_SOURCE/connectivity-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30061));
    }

    @Test
    void conflict_maps409() throws Exception {
        when(facade.update(eq(SourceCode.QUOTE), any(DataSourceConfigUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT));

        mockMvc.perform(
                        patch("/api/v1/datasource-configs/QUOTE")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mode\":\"MOCK\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }

    @Test
    void validationError_maps400() throws Exception {
        when(facade.update(eq(SourceCode.QUOTE), any(DataSourceConfigUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.PARAM_INVALID, "timeoutMillis: 须为正整数"));

        mockMvc.perform(
                        patch("/api/v1/datasource-configs/QUOTE")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"timeoutMillis\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("timeoutMillis: 须为正整数"));
    }
}
