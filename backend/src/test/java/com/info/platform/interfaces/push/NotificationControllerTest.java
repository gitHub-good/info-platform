package com.info.platform.interfaces.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.push.NotificationHistory;
import com.info.platform.application.push.NotificationView;
import com.info.platform.application.push.PushService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.push.PushType;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * NotificationController 切片测试（T14）：history 经 MockMvc 验证 HTTP/JSON/参数校验/鉴权； stream 端点直调验证 「认证取
 * userId + Last-Event-ID 透传 → PushService.openStream」编排（避开 MockMvc 异步分发的不稳定性）。 {@link PushService}
 * 用 Mockito mock，{@link GlobalExceptionHandler} 作 ControllerAdvice；认证上下文 @BeforeEach 经 {@link
 * UserContext#set} 模拟（生产由 JwtAuthFilter 写入）。
 */
class NotificationControllerTest {

    private MockMvc mockMvc;
    private PushService pushService;
    private NotificationController controller;

    @BeforeEach
    void setUp() {
        pushService = mock(PushService.class);
        controller = new NotificationController(pushService);
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

    // ---- history ----

    @Test
    void history_defaultCursorNoType_returns200WithHistory() throws Exception {
        // Arrange：一页不足 20 条 → nextCursor=null
        NotificationHistory history =
                new NotificationHistory(
                        List.of(
                                new NotificationView(
                                        10L,
                                        "anomaly",
                                        600519L,
                                        "SH600519",
                                        "42",
                                        "涨幅5%",
                                        1,
                                        Instant.parse("2026-09-21T02:00:05Z"),
                                        Instant.parse("2026-09-21T02:00:00Z"))),
                        null);
        when(pushService.history(eq(1L), eq(null), eq(null))).thenReturn(history);

        // Act + Assert
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].type").value("anomaly"))
                .andExpect(jsonPath("$.data.items[0].subjectId").value(600519))
                .andExpect(jsonPath("$.data.items[0].subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data.items[0].refId").value("42"))
                .andExpect(jsonPath("$.data.items[0].content").value("涨幅5%"))
                .andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void history_withLatest_delegatesLatestHistory() throws Exception {
        // P1-1：?latest=N 单次最近记录拉取（通知面板兜底），nextCursor 恒 null
        NotificationHistory history =
                new NotificationHistory(
                        List.of(
                                new NotificationView(
                                        11L,
                                        "anomaly",
                                        600519L,
                                        "SH600519",
                                        "42",
                                        "涨幅5%",
                                        1,
                                        Instant.parse("2026-09-21T02:00:05Z"),
                                        Instant.parse("2026-09-21T02:00:00Z"))),
                        null);
        when(pushService.latestHistory(eq(1L), eq(20), eq(null))).thenReturn(history);

        mockMvc.perform(get("/api/v1/notifications").param("latest", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(11))
                .andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        verify(pushService).latestHistory(1L, 20, null);
    }

    @Test
    void history_latestOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("latest", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(pushService, org.mockito.Mockito.never()).latestHistory(anyLong(), anyInt(), any());
    }

    @Test
    void history_withTypeAndCursor_passesFilter() throws Exception {
        NotificationHistory history = new NotificationHistory(List.of(), null);
        when(pushService.history(eq(1L), eq(10L), eq(PushType.ANOMALY))).thenReturn(history);

        mockMvc.perform(get("/api/v1/notifications").param("cursor", "10").param("type", "anomaly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        verify(pushService).history(1L, 10L, PushType.ANOMALY);
    }

    @Test
    void history_unknownType_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("type", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(pushService, org.mockito.Mockito.never())
                .history(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void history_unauthenticated_returns401AndCode1003() throws Exception {
        // Arrange：未认证（UserContext 为空，模拟 JWT 缺失/未走过滤器）
        UserContext.clear();

        // Act + Assert：受保护端点无认证上下文 → 1003/401
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ---- stream（直调，绕开 MockMvc 异步分发） ----

    @Test
    void stream_authedUserWithLastEventId_delegatesOpenStream() {
        SseEmitter emitter = new SseEmitter();
        when(pushService.openStream(eq(1L), eq(5L))).thenReturn(emitter);

        SseEmitter result = controller.stream(5L);

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(emitter);
        verify(pushService).openStream(1L, 5L);
    }

    @Test
    void stream_firstConnectNullLastEventId_delegates() {
        SseEmitter emitter = new SseEmitter();
        when(pushService.openStream(eq(1L), eq(null))).thenReturn(emitter);

        SseEmitter result = controller.stream(null);

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(emitter);
        verify(pushService).openStream(1L, null);
    }

    @Test
    void stream_unauthenticated_throwsTokenInvalid() {
        UserContext.clear();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.stream(null))
                .isInstanceOf(com.info.platform.domain.common.BusinessException.class)
                .hasMessageContaining("未认证上下文");
    }
}
