package com.info.platform.interfaces.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.retention.RetentionConfigFacade;
import com.info.platform.application.retention.RetentionConfigFacade.FieldLimits;
import com.info.platform.application.retention.RetentionConfigFacade.Windows;
import com.info.platform.application.retention.RetentionConfigFacade.WindowsUpdate;
import com.info.platform.application.retention.RetentionConfigFacade.WindowsView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RetentionController 切片测试（T72，契约 §4.3）：GET/PATCH /api/v1/retention/windows 形状 + 错误码映射 （2001 → 400
 * 字段级、30065 → 409）。standaloneSetup 独立 MockMvc（对齐 JobControllerTest 模式，JWT 保护由生产过滤器承担）。
 */
class RetentionControllerTest {

    private MockMvc mockMvc;
    private RetentionConfigFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(RetentionConfigFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RetentionController(facade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static WindowsView view() {
        Map<String, FieldLimits> limits = new LinkedHashMap<>();
        limits.put("jobExecutionLogDays", new FieldLimits(7, 30));
        limits.put("dataSourceEventDays", new FieldLimits(2, 14));
        limits.put("llmCallLogDays", new FieldLimits(35, 90));
        limits.put("readingEventDays", new FieldLimits(35, 90));
        limits.put("newsItemDays", new FieldLimits(30, 180));
        return new WindowsView(new Windows(30, 14, 90, 90, 180), limits, "2026-09-22T01:00:00Z");
    }

    @Test
    void windows_returns200WithWindowsLimitsUpdatedAt() throws Exception {
        when(facade.view()).thenReturn(view());

        mockMvc.perform(get("/api/v1/retention/windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.windows.jobExecutionLogDays").value(30))
                .andExpect(jsonPath("$.data.windows.dataSourceEventDays").value(14))
                .andExpect(jsonPath("$.data.windows.llmCallLogDays").value(90))
                .andExpect(jsonPath("$.data.windows.readingEventDays").value(90))
                .andExpect(jsonPath("$.data.windows.newsItemDays").value(180))
                .andExpect(jsonPath("$.data.limits.jobExecutionLogDays.min").value(7))
                .andExpect(jsonPath("$.data.limits.jobExecutionLogDays.default").value(30))
                .andExpect(jsonPath("$.data.limits.dataSourceEventDays.min").value(2))
                .andExpect(jsonPath("$.data.limits.llmCallLogDays.min").value(35))
                .andExpect(jsonPath("$.data.limits.readingEventDays.default").value(90))
                .andExpect(jsonPath("$.data.limits.newsItemDays.min").value(30))
                .andExpect(jsonPath("$.data.limits.newsItemDays.default").value(180))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T01:00:00Z"));
    }

    @Test
    void updateWindows_returns200WithRefreshedView() throws Exception {
        when(facade.update(any(WindowsUpdate.class))).thenReturn(view());

        mockMvc.perform(
                        patch("/api/v1/retention/windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":14,"
                                                + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.windows.jobExecutionLogDays").value(30))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-22T01:00:00Z"));
    }

    @Test
    void updateWindows_illegalValue_400WithFieldLevelMessage() throws Exception {
        // 误配双重防御·接口层：低于下限 → 2001/400 字段级 msg（原值保留）
        when(facade.update(any(WindowsUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PARAM_INVALID, "jobExecutionLogDays: 须 >= 7"));

        mockMvc.perform(
                        patch("/api/v1/retention/windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"jobExecutionLogDays\":0,\"dataSourceEventDays\":14,"
                                                + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("jobExecutionLogDays: 须 >= 7"));
    }

    @Test
    void updateWindows_concurrentConflict_409() throws Exception {
        when(facade.update(any(WindowsUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.CONFIG_CONFLICT, "配置已被并发修改: retention.global，请刷新后重试"));

        mockMvc.perform(
                        patch("/api/v1/retention/windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":14,"
                                                + "\"llmCallLogDays\":90,\"readingEventDays\":90,\"newsItemDays\":180,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T00:00:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }
}
