package com.info.platform.interfaces.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.DailyRecommendationResult;
import com.info.platform.application.ai.DailyRecommendationService;
import com.info.platform.domain.ai.TopRecommendation;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RecommendationController 切片测试（T23）：GET /api/v1/recommendations/daily → Top5 + 免责。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文、不跑 JwtAuthFilter），
 * {@link DailyRecommendationService} 用 Mockito mock。认证上下文在 @BeforeEach 经 {@link UserContext#set}
 * 模拟（生产由 JwtAuthFilter 写入），@AfterEach 清空。
 */
class RecommendationControllerTest {

    private MockMvc mockMvc;
    private DailyRecommendationService dailyRecommendationService;

    @BeforeEach
    void setUp() {
        dailyRecommendationService = mock(DailyRecommendationService.class);
        RecommendationController controller =
                new RecommendationController(dailyRecommendationService);
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
    void daily_done_returns200WithTop5AndDisclaimer() throws Exception {
        // Arrange：AI 生成 Top5
        when(dailyRecommendationService.generateDaily(1L))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_DONE,
                                List.of(
                                        new TopRecommendation("SH600519", "贵州茅台", "信息面活跃", 1),
                                        new TopRecommendation("SZ000858", "五粮液", "公告密集", 2)),
                                "AI 生成，非投资建议",
                                false));

        // Act + Assert：200 + status=1 + Top5 + disclaimer
        mockMvc.perform(get("/api/v1/recommendations/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.topRecommend[0].subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data.topRecommend[0].rank").value(1))
                .andExpect(jsonPath("$.data.topRecommend[1].subjectCode").value("SZ000858"))
                .andExpect(jsonPath("$.data.disclaimer").value("AI 生成，非投资建议"));
    }

    @Test
    void daily_emptyPool_returns200WithEmptyArray() throws Exception {
        // Arrange：自选池空 → status=3 + 空 Top5
        when(dailyRecommendationService.generateDaily(1L))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_EMPTY,
                                List.of(),
                                "AI 生成，非投资建议",
                                false));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.topRecommend").isEmpty())
                .andExpect(jsonPath("$.data.disclaimer").value("AI 生成，非投资建议"));
    }

    @Test
    void daily_fallback_returns200WithFallbackFlag() throws Exception {
        // Arrange：规则兜底（status=2）
        when(dailyRecommendationService.generateDaily(1L))
                .thenReturn(
                        new DailyRecommendationResult(
                                DailyRecommendationResult.STATUS_FALLBACK,
                                List.of(new TopRecommendation("SH600519", "贵州茅台", "活跃度排序", 1)),
                                "AI 生成，非投资建议",
                                true));

        // Act + Assert：status=2 仍 200 + Top5
        mockMvc.perform(get("/api/v1/recommendations/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2));
    }
}
