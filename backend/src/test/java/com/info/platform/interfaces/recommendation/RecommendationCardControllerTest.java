package com.info.platform.interfaces.recommendation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.info.platform.application.recommendation.RecommendationCardDetailView;
import com.info.platform.application.recommendation.RecommendationCardListView;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 推荐中心接口切片测试（T133，方案 §4.8）：GET /api/v1/recommendations 卡片流（level/eventType/direction/read 可空筛选 +
 * beforeId 游标 + limit 缺省 20 ≤50）与 GET /{id} 详情（logicInputs 抽检面）；30080 不存在/非本人、30082 筛选非法。 认证上下文经
 * UserContext.set 模拟（生产由 JwtAuthFilter 写入）。
 */
class RecommendationCardControllerTest {

    private MockMvc mockMvc;

    private RecommendationQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RecommendationCardController(queryService))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(7L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static RecommendationCardListView.CardView cardView(long id) {
        return new RecommendationCardListView.CardView(
                id,
                9000L + id,
                "BUYBACK_CHANGE",
                "HIGH",
                "BULLISH",
                "P1",
                List.of("食品饮料"),
                List.of(
                        new RecommendationCardListView.SubjectView(
                                "SH600519", "贵州茅台", "食品饮料", true)),
                "贵州茅台公告回购计划——该事件直接涉及你关注的标的贵州茅台。",
                "贵州茅台公告回购计划，拟回购金额不超过30亿元",
                List.of(new RecommendationCardListView.FigureView("回购金额上限", "30", "亿元")),
                "拟回购金额不超过30亿元",
                8000L + id,
                "贵州茅台拟回购不超30亿元",
                "https://example.com/n/1",
                Instant.parse("2026-09-22T07:30:00Z"),
                "PUSHED",
                Instant.parse("2026-09-22T07:31:00Z"),
                Instant.parse("2026-09-22T07:30:30Z"),
                false,
                false,
                null);
    }

    @Test
    void list_returnsWrappedCards() throws Exception {
        // Arrange
        when(queryService.list(
                        eq(7L),
                        eq("P1"),
                        eq("BUYBACK_CHANGE"),
                        eq("BULLISH"),
                        eq("0"),
                        eq(100L),
                        eq(20)))
                .thenReturn(new RecommendationCardListView(42L, List.of(cardView(9L)), 9L));

        // Act + Assert：六参数透传 + Result 包装 + 卡片字段线格式
        mockMvc.perform(
                        get("/api/v1/recommendations")
                                .param("level", "P1")
                                .param("eventType", "BUYBACK_CHANGE")
                                .param("direction", "BULLISH")
                                .param("read", "0")
                                .param("beforeId", "100")
                                .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.nextBeforeId").value(9))
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].level").value("P1"))
                .andExpect(jsonPath("$.data.items[0].logicChain").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].subjects[0].code").value("SH600519"))
                .andExpect(jsonPath("$.data.items[0].subjects[0].inWatchlist").value(true))
                .andExpect(jsonPath("$.data.items[0].newsTitle").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].pushStatus").value("PUSHED"));
    }

    @Test
    void list_invalidFilter_maps30082With400() throws Exception {
        // Arrange
        when(queryService.list(eq(7L), eq("P9"), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.RECOMMENDATION_FILTER_INVALID, "level: 须为 P1 / P2 / P3"));

        // Act + Assert：筛选非法 30082（400）
        mockMvc.perform(get("/api/v1/recommendations").param("level", "P9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30082));
    }

    @Test
    void list_limitOutOfRange_rejectedNotTruncated() throws Exception {
        // Arrange：limit 越界拒绝不截断（M9 口径）
        when(queryService.list(eq(7L), eq(null), eq(null), eq(null), eq(null), eq(null), eq(51)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.RECOMMENDATION_FILTER_INVALID, "limit: 须在 1~50"));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30082));
    }

    @Test
    void detail_returnsCardWithLogicInputs() throws Exception {
        // Arrange：详情 = 卡片视图 + logic_inputs 快照（抽检对账面）
        when(queryService.detail(7L, 9L))
                .thenReturn(new RecommendationCardDetailView(cardView(9L), "{\"level\":\"P1\"}"));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.card.id").value(9))
                .andExpect(jsonPath("$.data.card.logicChain").isNotEmpty())
                .andExpect(jsonPath("$.data.logicInputs").isNotEmpty());
    }

    @Test
    void detail_missingOrForeignCard_maps30080With404() throws Exception {
        // Arrange：卡片不存在/非本人同一 30080 语义（不泄露存在性）
        when(queryService.detail(7L, 424242L))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations/424242"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30080));
    }
}
