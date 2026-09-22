package com.info.platform.interfaces.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.ReadingEventService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ReadingEventController 切片测试（T29）：POST /api/v1/reading-events 落库/去重响应 + 参数校验 400。
 *
 * <p>standaloneSetup 独立装配 MockMvc（不加载上下文、不跑 JwtAuthFilter），ReadingEventService 用 Mockito
 * mock，认证上下文经 UserContext.set 模拟。对齐 RecommendationControllerTest 模式。
 */
class ReadingEventControllerTest {

    private MockMvc mockMvc;
    private ReadingEventService readingEventService;

    @BeforeEach
    void setUp() {
        readingEventService = mock(ReadingEventService.class);
        ReadingEventController controller = new ReadingEventController(readingEventService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(1L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void record_normal_returns200RecordedTrue() throws Exception {
        // Arrange：首次上报落库
        when(readingEventService.record(
                        eq(1L), eq("SUBJECT_DETAIL"), eq("SH600519"), eq("SH600519"), any()))
                .thenReturn(true);

        // Act + Assert：200 + recorded=true
        mockMvc.perform(
                        post("/api/v1/reading-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentType\":\"SUBJECT_DETAIL\",\"contentRef\":\"SH600519\","
                                                + "\"subjectCode\":\"SH600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recorded").value(true));
    }

    @Test
    void record_deduplicated_returns200RecordedFalse() throws Exception {
        // Arrange：窗口内重复上报被去重（幂等友好，非错误）
        when(readingEventService.record(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(false);

        // Act + Assert：仍 200 + recorded=false
        mockMvc.perform(
                        post("/api/v1/reading-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"contentType\":\"AI_BRIEF\",\"contentRef\":\"7\",\"subjectId\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recorded").value(false));
    }

    @Test
    void record_blankContentRef_returns400() throws Exception {
        // Arrange / Act / Assert：contentRef 空白 → 校验失败 2001/400
        mockMvc.perform(
                        post("/api/v1/reading-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentType\":\"POLICY\",\"contentRef\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void record_missingContentType_returns400() throws Exception {
        // Arrange / Act / Assert：contentType 缺失 → 400
        mockMvc.perform(
                        post("/api/v1/reading-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentRef\":\"SH600519\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void record_policyWithoutSubject_ok() throws Exception {
        // Arrange：政策阅读无标的关联（subjectCode/subjectId 均空）
        when(readingEventService.record(eq(1L), eq("POLICY"), eq("42"), isNull(), isNull()))
                .thenReturn(true);

        // Act + Assert：合法载荷 200
        mockMvc.perform(
                        post("/api/v1/reading-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contentType\":\"POLICY\",\"contentRef\":\"42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recorded").value(true));
    }
}
