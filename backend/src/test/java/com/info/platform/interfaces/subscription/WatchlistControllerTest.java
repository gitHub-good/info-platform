package com.info.platform.interfaces.subscription;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.subscription.WatchlistItemView;
import com.info.platform.application.subscription.WatchlistService;
import com.info.platform.application.subscription.WatchlistView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * WatchlistController 切片测试（T11）：CRUD 端点 + 幂等冲突码 + 行级权限码 + 参数校验。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文、不跑 JwtAuthFilter），
 * {@link WatchlistService} 用 Mockito mock，{@link GlobalExceptionHandler} 作 ControllerAdvice。
 * 受保护接口的认证上下文在 @BeforeEach 经 {@link UserContext#set} 模拟（生产由 JwtAuthFilter 写入），@AfterEach 清空。
 */
class WatchlistControllerTest {

    private MockMvc mockMvc;
    private WatchlistService watchlistService;

    private static final WatchlistView WATCHLIST_VIEW =
            new WatchlistView(
                    1L,
                    "我的清单",
                    "备注",
                    1,
                    List.of(new WatchlistItemView(10L, 100L, new BigDecimal("3.00"), 1)));
    private static final WatchlistItemView ITEM_VIEW =
            new WatchlistItemView(10L, 100L, new BigDecimal("5.00"), 1);

    @BeforeEach
    void setUp() {
        watchlistService = mock(WatchlistService.class);
        WatchlistController controller = new WatchlistController(watchlistService);
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
    void list_returns200WithOwnWatchlists() throws Exception {
        when(watchlistService.listMyWatchlists()).thenReturn(List.of(WATCHLIST_VIEW));

        mockMvc.perform(get("/api/v1/watchlists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("我的清单"))
                .andExpect(jsonPath("$.data[0].items[0].subjectId").value(100));
    }

    // ---- create ----

    @Test
    void create_returns200WithCreatedWatchlist() throws Exception {
        when(watchlistService.createWatchlist(eq("我的清单"), eq("备注"))).thenReturn(WATCHLIST_VIEW);

        mockMvc.perform(
                        post("/api/v1/watchlists")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "1|我的清单")
                                .content("{\"name\":\"我的清单\",\"remark\":\"备注\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void create_sameName_returns409AndCode30011() throws Exception {
        // 幂等冲突（同名）：30011/409
        when(watchlistService.createWatchlist(eq("我的清单"), any()))
                .thenThrow(new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST, "清单名已存在"));

        mockMvc.perform(
                        post("/api/v1/watchlists")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "1|我的清单")
                                .content("{\"name\":\"我的清单\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30011));
    }

    @Test
    void create_blankName_returns400AndCode2001() throws Exception {
        // @Valid @NotBlank 触发 → 2xxx
        mockMvc.perform(
                        post("/api/v1/watchlists")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(watchlistService, never()).createWatchlist(any(), any());
    }

    // ---- get single ----

    @Test
    void get_returns200WithWatchlist() throws Exception {
        when(watchlistService.getWatchlist(1L)).thenReturn(WATCHLIST_VIEW);

        mockMvc.perform(get("/api/v1/watchlists/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.items[0].subjectId").value(100));
    }

    @Test
    void get_notFound_returns404AndCode30010() throws Exception {
        when(watchlistService.getWatchlist(999L))
                .thenThrow(new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND));

        mockMvc.perform(get("/api/v1/watchlists/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30010));
    }

    @Test
    void get_forbidden_returns403AndCode30012() throws Exception {
        // 行级越权：30012/403
        when(watchlistService.getWatchlist(2L))
                .thenThrow(new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN));

        mockMvc.perform(get("/api/v1/watchlists/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30012));
    }

    // ---- add item ----

    @Test
    void addItem_returns200WithCreatedItem() throws Exception {
        when(watchlistService.addItem(eq(1L), eq(100L), any())).thenReturn(ITEM_VIEW);

        mockMvc.perform(
                        post("/api/v1/watchlists/1/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "1|1|100")
                                .content("{\"subjectId\":100,\"anomalyThreshold\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.subjectId").value(100));
    }

    @Test
    void addItem_alreadyInList_returns409AndCode30011() throws Exception {
        // 幂等冲突（已在清单）：30011/409
        when(watchlistService.addItem(eq(1L), eq(100L), any()))
                .thenThrow(new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST));

        mockMvc.perform(
                        post("/api/v1/watchlists/1/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "1|1|100")
                                .content("{\"subjectId\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30011));
    }

    @Test
    void addItem_watchlistNotFound_returns404AndCode30010() throws Exception {
        when(watchlistService.addItem(eq(999L), eq(100L), any()))
                .thenThrow(new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND));

        mockMvc.perform(
                        post("/api/v1/watchlists/999/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30010));
    }

    @Test
    void addItem_forbidden_returns403AndCode30012() throws Exception {
        // 行级越权：30012/403
        when(watchlistService.addItem(eq(2L), eq(100L), any()))
                .thenThrow(new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN));

        mockMvc.perform(
                        post("/api/v1/watchlists/2/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30012));
    }

    @Test
    void addItem_blankSubjectId_returns400AndCode2001() throws Exception {
        mockMvc.perform(
                        post("/api/v1/watchlists/1/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(watchlistService, never()).addItem(any(), any(), any());
    }

    @Test
    void addItem_negativeThreshold_returns400AndCode2001() throws Exception {
        // @PositiveOrZero 在接口层兜底，不进服务
        mockMvc.perform(
                        post("/api/v1/watchlists/1/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100,\"anomalyThreshold\":-1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(watchlistService, never()).addItem(any(), any(), any());
    }

    // ---- remove ----

    @Test
    void removeItem_returns200() throws Exception {
        // removeItem 返回 void，mock 默认 doNothing
        mockMvc.perform(delete("/api/v1/watchlists/1/items/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(watchlistService).removeItem(1L, 10L);
    }

    @Test
    void removeItem_forbidden_returns403AndCode30012() throws Exception {
        doThrow(new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN))
                .when(watchlistService)
                .removeItem(2L, 10L);

        mockMvc.perform(delete("/api/v1/watchlists/2/items/10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30012));
    }

    // ---- patch threshold ----

    @Test
    void updateThreshold_returns200WithUpdatedItem() throws Exception {
        when(watchlistService.updateItemThreshold(eq(1L), eq(10L), any())).thenReturn(ITEM_VIEW);

        mockMvc.perform(
                        patch("/api/v1/watchlists/1/items/10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"anomalyThreshold\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10));
    }

    @Test
    void updateThreshold_forbidden_returns403AndCode30012() throws Exception {
        doThrow(new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN))
                .when(watchlistService)
                .updateItemThreshold(eq(2L), eq(10L), any());

        mockMvc.perform(
                        patch("/api/v1/watchlists/2/items/10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"anomalyThreshold\":5.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30012));
    }

    @Test
    void updateThreshold_blankThreshold_returns400AndCode2001() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/watchlists/1/items/10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"anomalyThreshold\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(watchlistService, never()).updateItemThreshold(any(), any(), any());
    }
}
