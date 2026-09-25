package com.info.platform.interfaces.feed;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.feed.NewsItemView;
import com.info.platform.application.feed.NewsItemsCursorView;
import com.info.platform.application.feed.NewsItemsPagedView;
import com.info.platform.application.feed.NewsItemsQueryService;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * NewsItemsController 切片测试（T104，方案 §4.5 + M9 双模式分派）：游标模式（beforeId/limit 默认 20
 * ≤50）与页码模式（page/size/total）互斥校验 + sourceId 过滤透传。standalone MockMvc，service mock。
 */
class NewsItemsControllerTest {

    private MockMvc mockMvc;
    private NewsItemsQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(NewsItemsQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new NewsItemsController(queryService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static NewsItemView view(long id, String title) {
        return new NewsItemView(
                id,
                7L,
                "t104_src",
                "测试源",
                title,
                "摘要",
                "https://example.com/n",
                "作者",
                "2026-09-22T01:31:00Z",
                "2026-09-22T01:31:30Z");
    }

    @Test
    void list_cursorMode_returnsItemsAndNextBeforeId() throws Exception {
        when(queryService.listCursor(eq(null), eq(null), eq(20)))
                .thenReturn(new NewsItemsCursorView(List.of(view(2, "b"), view(1, "a")), 1L));

        mockMvc.perform(get("/api/v1/news-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("b"))
                .andExpect(jsonPath("$.data.items[0].sourceCode").value("t104_src"))
                .andExpect(jsonPath("$.data.nextBeforeId").value(1));
    }

    @Test
    void list_cursorMode_passesSourceIdAndBeforeIdAndLimit() throws Exception {
        when(queryService.listCursor(eq(7L), eq(100L), eq(5)))
                .thenReturn(new NewsItemsCursorView(List.of(view(99, "x")), null));

        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("sourceId", "7")
                                .param("beforeId", "100")
                                .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.nextBeforeId").doesNotExist());
    }

    @Test
    void list_limitOver50_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_limitBelow1_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_pageMode_returnsTotalPageSize() throws Exception {
        when(queryService.listPaged(eq(7L), eq(2), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(3, "c")), 41L, 2, 20));

        mockMvc.perform(get("/api/v1/news-items").param("sourceId", "7").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("c"))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void list_pageWithBeforeId_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("page", "2").param("beforeId", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_sizeWithoutPage_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_limitWithPage_rejected400() throws Exception {
        // limit 为游标模式专属参数（契约确定性优先于宽容，M9 同口径）
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("limit", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_invalidSourceId_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("sourceId", "abc"))
                .andExpect(status().isBadRequest());
    }
}
