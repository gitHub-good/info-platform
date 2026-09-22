package com.info.platform.interfaces.subscription;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.subscription.SubscriptionListView;
import com.info.platform.application.subscription.SubscriptionService;
import com.info.platform.application.subscription.SubscriptionView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionType;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SubscriptionController 切片测试（T26）：GET/POST/DELETE + 幂等语义 + 行级权限码 + 参数校验。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文、不跑 JwtAuthFilter），
 * {@link SubscriptionService} 用 Mockito mock，{@link GlobalExceptionHandler} 作 ControllerAdvice。
 * 受保护接口的认证上下文在 @BeforeEach 经 {@link UserContext#set} 模拟（生产由 JwtAuthFilter 写入），@AfterEach 清空。
 */
class SubscriptionControllerTest {

    private MockMvc mockMvc;
    private SubscriptionService subscriptionService;

    private static final SubscriptionView SUBSCRIPTION_VIEW =
            new SubscriptionView(10L, 2, "600519", 1, 1);

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        SubscriptionController controller = new SubscriptionController(subscriptionService);
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

    // ---- list ----

    @Test
    void list_returns200WithSubscriptions() throws Exception {
        when(subscriptionService.listSubscriptions(isNull(), isNull()))
                .thenReturn(new SubscriptionListView(List.of(SUBSCRIPTION_VIEW), null));

        mockMvc.perform(get("/api/v1/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].subType").value(2))
                .andExpect(jsonPath("$.data.items[0].subKey").value("600519"))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void list_withTypeFilter_passesTypeToService() throws Exception {
        when(subscriptionService.listSubscriptions(eq(SubscriptionType.SUBJECT), isNull()))
                .thenReturn(new SubscriptionListView(List.of(SUBSCRIPTION_VIEW), 10L));

        mockMvc.perform(get("/api/v1/subscriptions").param("type", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nextCursor").value(10));
    }

    @Test
    void list_invalidType_returns400AndCode2001() throws Exception {
        // type=9 非法（1~4）→ PARAM_INVALID/400
        mockMvc.perform(get("/api/v1/subscriptions").param("type", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(subscriptionService, org.mockito.Mockito.never()).listSubscriptions(any(), any());
    }

    // ---- create ----

    @Test
    void create_returns200WithCreatedSubscription() throws Exception {
        when(subscriptionService.subscribe(
                        eq(SubscriptionType.SUBJECT), eq("600519"), eq(SubscriptionChannel.IN_APP)))
                .thenReturn(SUBSCRIPTION_VIEW);

        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "1|2|600519")
                                .content("{\"subType\":2,\"subKey\":\"600519\",\"channel\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.subType").value(2))
                .andExpect(jsonPath("$.data.subKey").value("600519"));
    }

    @Test
    void create_channelOmitted_defaultsToInApp() throws Exception {
        // channel 缺省 → 控制器传 null → service/entity 层兜底应用内渠道
        when(subscriptionService.subscribe(eq(SubscriptionType.SUBJECT), eq("600519"), isNull()))
                .thenReturn(SUBSCRIPTION_VIEW);

        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subType\":2,\"subKey\":\"600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(subscriptionService).subscribe(eq(SubscriptionType.SUBJECT), eq("600519"), isNull());
    }

    @Test
    void create_nullSubType_returns400AndCode2001() throws Exception {
        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subKey\":\"600519\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(subscriptionService, org.mockito.Mockito.never()).subscribe(any(), any(), any());
    }

    @Test
    void create_subTypeOutOfRange_returns400AndCode2001() throws Exception {
        // subType=5 超出 1~4 → @Max 触发
        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subType\":5,\"subKey\":\"600519\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(subscriptionService, org.mockito.Mockito.never()).subscribe(any(), any(), any());
    }

    @Test
    void create_blankSubKey_returns400AndCode2001() throws Exception {
        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subType\":2,\"subKey\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(subscriptionService, org.mockito.Mockito.never()).subscribe(any(), any(), any());
    }

    @Test
    void create_channelOutOfRange_returns400AndCode2001() throws Exception {
        // channel=3 超出 1~2 → @Max 触发
        mockMvc.perform(
                        post("/api/v1/subscriptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subType\":2,\"subKey\":\"600519\",\"channel\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(subscriptionService, org.mockito.Mockito.never()).subscribe(any(), any(), any());
    }

    // ---- unsubscribe ----

    @Test
    void unsubscribe_returns200() throws Exception {
        // unsubscribe 返回 void，mock 默认 doNothing
        mockMvc.perform(delete("/api/v1/subscriptions/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(subscriptionService).unsubscribe(10L);
    }

    @Test
    void unsubscribe_notFound_returns404AndCode30050() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND))
                .when(subscriptionService)
                .unsubscribe(999L);

        mockMvc.perform(delete("/api/v1/subscriptions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30050));
    }

    @Test
    void unsubscribe_forbidden_returns403AndCode30051() throws Exception {
        // 行级越权：30051/403
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SUBSCRIPTION_FORBIDDEN))
                .when(subscriptionService)
                .unsubscribe(20L);

        mockMvc.perform(delete("/api/v1/subscriptions/20"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30051));
    }
}
