package com.info.platform.interfaces.subscription;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.subscription.FeedItem;
import com.info.platform.application.subscription.FeedItemType;
import com.info.platform.application.subscription.FeedListView;
import com.info.platform.application.subscription.FeedService;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FeedController 切片测试（T27）：GET /api/v1/feed/personal。standalone MockMvc（不加载 Spring 上下文），
 * FeedService Mockito mock，UserContext @BeforeEach 模拟 JwtAuthFilter 写入。
 *
 * <p>DEFECT-2 回归（M4）：cursor 三态——非法格式（zzz）→ 400/2001 字段级原因（勿落兜底 500/50000）、 合法数值透传 service、缺省首页。
 */
class FeedControllerTest {

    private MockMvc mockMvc;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        feedService = mock(FeedService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new FeedController(feedService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(1L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void personal_returns200WithItemsAndNextCursor() throws Exception {
        // Arrange：1 条命中公告 + 1 条每日推荐
        when(feedService.getPersonalFeed(1L, null))
                .thenReturn(
                        new FeedListView(
                                List.of(
                                        new FeedItem(
                                                1L,
                                                "announce:a1",
                                                FeedItemType.ANNOUNCE,
                                                "2026年半年度报告",
                                                "",
                                                "2026-09-14T16:00:00Z",
                                                "公告",
                                                "https://ex/ann/1",
                                                "SH600519",
                                                "贵州茅台",
                                                "标的订阅:贵州茅台",
                                                List.of("贵州茅台")),
                                        new FeedItem(
                                                2L,
                                                "recommendation:SH600036",
                                                FeedItemType.RECOMMENDATION,
                                                "招商银行",
                                                "信息面活跃",
                                                "2026-09-21T08:00:00Z",
                                                "每日推荐",
                                                null,
                                                "SH600036",
                                                "招商银行",
                                                "每日推荐",
                                                null)),
                                2L));

        // Act + Assert：200 + code=0 + 命中原因/关键词 + 稳定 contentId（M11 契约增量）+ nextCursor
        mockMvc.perform(get("/api/v1/feed/personal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].type").value("announce"))
                .andExpect(jsonPath("$.data.items[0].title").value("2026年半年度报告"))
                .andExpect(jsonPath("$.data.items[0].contentId").value("announce:a1"))
                .andExpect(jsonPath("$.data.items[0].matchReason").value("标的订阅:贵州茅台"))
                .andExpect(jsonPath("$.data.items[0].keywords[0]").value("贵州茅台"))
                .andExpect(jsonPath("$.data.items[1].type").value("recommendation"))
                .andExpect(jsonPath("$.data.items[1].contentId").value("recommendation:SH600036"))
                .andExpect(jsonPath("$.data.items[1].matchReason").value("每日推荐"))
                .andExpect(jsonPath("$.data.items[1].keywords").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value(2));
    }

    @Test
    void personal_withCursor_passesCursorToService() throws Exception {
        // Arrange：翻页游标透传 service
        when(feedService.getPersonalFeed(1L, 20L)).thenReturn(new FeedListView(List.of(), null));

        // Act + Assert
        mockMvc.perform(get("/api/v1/feed/personal").param("cursor", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void personal_withIllegalCursor_returns400ParamInvalid() throws Exception {
        // DEFECT-2 回归（修前红）：cursor=zzz 类型不匹配须按参数校验拒绝（400/2001 字段级原因），
        // 而非被兜底异常处理器吞成 500/50000（与三域配置校验口径一致）
        mockMvc.perform(get("/api/v1/feed/personal").param("cursor", "zzz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(containsString("cursor")));
    }
}
