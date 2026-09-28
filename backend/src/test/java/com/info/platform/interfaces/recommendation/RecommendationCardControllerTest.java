package com.info.platform.interfaces.recommendation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.info.platform.application.recommendation.RecommendationCardDetailView;
import com.info.platform.application.recommendation.RecommendationCardListView;
import com.info.platform.application.recommendation.RecommendationCardPageView;
import com.info.platform.application.recommendation.RecommendationFeedbackService;
import com.info.platform.application.recommendation.RecommendationQueryService;
import com.info.platform.application.recommendation.RecommendationStatsView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 推荐中心接口切片测试（T133 → T134 扩 feedback/read/stats 三端点，方案 §4.8）：GET /api/v1/recommendations
 * 卡片流（level/eventType/direction/read 可空筛选 + beforeId 游标 + limit 缺省 20 ≤50）与 GET /{id}
 * 详情（logicInputs 抽检面）；POST /{id}/feedback 四动作（30080/30081）、POST /{id}/read 已读（幂等 200 直返）、GET /stats
 * adopt-v1 采纳统计（30082 日期非法）。 认证上下文经 UserContext.set 模拟（生产由 JwtAuthFilter 写入）。
 */
class RecommendationCardControllerTest {

    private MockMvc mockMvc;

    private RecommendationQueryService queryService;

    private RecommendationFeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        feedbackService = mock(RecommendationFeedbackService.class);
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new RecommendationCardController(queryService, feedbackService))
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

    // ---- T220（M25 V3.0）：page/size 页码模式（M9 PageQuery 双模式分派，beforeId 游标保留兼容）----

    @Test
    void list_pageMode_returnsPagedViewWithoutCursorFields() throws Exception {
        // Arrange：page 出现即页码模式（{total, items, page, size}，无 nextBeforeId）
        when(queryService.listPaged(eq(7L), eq(null), eq(null), eq(null), eq(null), eq(2), eq(5)))
                .thenReturn(new RecommendationCardPageView(42L, List.of(cardView(9L)), 2, 5));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.nextBeforeId").doesNotExist());
    }

    @Test
    void list_pageMode_passesFourFilters() throws Exception {
        // Arrange：四维筛选与页码模式正交
        when(queryService.listPaged(
                        eq(7L),
                        eq("P1"),
                        eq("BUYBACK_CHANGE"),
                        eq("BULLISH"),
                        eq("0"),
                        eq(1),
                        eq(20)))
                .thenReturn(new RecommendationCardPageView(1L, List.of(), 1, 20));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/recommendations")
                                .param("level", "P1")
                                .param("eventType", "BUYBACK_CHANGE")
                                .param("direction", "BULLISH")
                                .param("read", "0")
                                .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20)); // size 缺省 20（PageQuery.DEFAULT_SIZE）
    }

    @Test
    void list_pageWithBeforeId_mutuallyExclusive_400() throws Exception {
        // Arrange/Act/Assert：page 与 beforeId 互斥（2001，M9 契约确定性）
        mockMvc.perform(get("/api/v1/recommendations").param("page", "1").param("beforeId", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("page 与 cursor 互斥，只能二选一"));
    }

    @Test
    void list_sizeWithoutPageOrLimitWithPage_400() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("缺少 page 参数（size 仅页码模式可用）"));

        // limit 为游标模式专属（页码模式请用 size——M9/资讯库同例）
        mockMvc.perform(get("/api/v1/recommendations").param("page", "1").param("limit", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("limit 仅游标模式可用（页码模式请使用 size）"));
    }

    @Test
    void list_pageModeBounds_rejectedNotTruncated() throws Exception {
        // 越界拒绝不截断（page<1 / page 超上限 / size<1 / size>50）
        mockMvc.perform(get("/api/v1/recommendations").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/recommendations").param("page", "1000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/recommendations").param("page", "1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/recommendations").param("page", "1").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_cursorMode_regression_pageAbsent() throws Exception {
        // 页码+游标模式并存回归：page 缺席走既有游标路径（nextBeforeId 照常）
        when(queryService.list(eq(7L), eq(null), eq(null), eq(null), eq(null), eq(100L), eq(null)))
                .thenReturn(new RecommendationCardListView(42L, List.of(cardView(9L)), 9L));

        mockMvc.perform(get("/api/v1/recommendations").param("beforeId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextBeforeId").value(9))
                .andExpect(jsonPath("$.data.page").doesNotExist());
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

    // ---- T134：feedback / read / stats 三端点 ----

    @Test
    void feedback_dislike_returnsMuteUntilAndEscalated() throws Exception {
        // Arrange
        when(feedbackService.feedback(7L, 9L, "DISLIKE", null))
                .thenReturn(
                        new RecommendationFeedbackService.FeedbackResult(
                                "2026-09-29T08:00:00Z", false));

        // Act + Assert：body {action} 透传 + {muteUntil, escalated} 响应
        mockMvc.perform(
                        post("/api/v1/recommendations/9/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"DISLIKE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.muteUntil").value("2026-09-29T08:00:00Z"))
                .andExpect(jsonPath("$.data.escalated").value(false));
    }

    @Test
    void feedback_addWatchlist_passesSubjectCode() throws Exception {
        // Arrange
        when(feedbackService.feedback(7L, 9L, "ADD_WATCHLIST", "SH600519"))
                .thenReturn(new RecommendationFeedbackService.FeedbackResult(null, null));

        // Act + Assert：subjectCode 透传（必填校验在服务层）
        mockMvc.perform(
                        post("/api/v1/recommendations/9/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"ADD_WATCHLIST\",\"subjectCode\":\"SH600519\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.muteUntil").doesNotExist());
    }

    @Test
    void feedback_invalidActionOrMissingCard_maps30081And30080() throws Exception {
        // Arrange
        when(feedbackService.feedback(7L, 9L, "LIKE", null))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_FEEDBACK_INVALID));
        when(feedbackService.feedback(7L, 424242L, "USEFUL", null))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));

        // Act + Assert
        mockMvc.perform(
                        post("/api/v1/recommendations/9/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"LIKE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30081));
        mockMvc.perform(
                        post("/api/v1/recommendations/424242/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"USEFUL\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30080));
    }

    @Test
    void read_marksRead_idempotent200() throws Exception {
        // Act + Assert：已读端点幂等 200 直返（无载荷）
        mockMvc.perform(post("/api/v1/recommendations/9/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(feedbackService).markRead(7L, 9L);
    }

    @Test
    void read_missingCard_maps30080() throws Exception {
        // Arrange
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND))
                .when(feedbackService)
                .markRead(7L, 424242L);

        // Act + Assert
        mockMvc.perform(post("/api/v1/recommendations/424242/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30080));
    }

    @Test
    void stats_returnsAdoptV1Fields() throws Exception {
        // Arrange
        when(queryService.stats(7L, "2026-09-22"))
                .thenReturn(new RecommendationStatsView("2026-09-22", 5L, 3L, 4L, 0.5, "adopt-v1"));

        // Act + Assert：date 可缺省；五字段 + basis
        mockMvc.perform(get("/api/v1/recommendations/stats").param("date", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.date").value("2026-09-22"))
                .andExpect(jsonPath("$.data.pushDelivered").value(5))
                .andExpect(jsonPath("$.data.viewExposed").value(3))
                .andExpect(jsonPath("$.data.adopted").value(4))
                .andExpect(jsonPath("$.data.adoptRate").value(0.5))
                .andExpect(jsonPath("$.data.basis").value("adopt-v1"));
    }

    @Test
    void stats_invalidDate_maps30082With400() throws Exception {
        // Arrange
        when(queryService.stats(7L, "bad-date"))
                .thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_FILTER_INVALID));

        // Act + Assert
        mockMvc.perform(get("/api/v1/recommendations/stats").param("date", "bad-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30082));
    }
}
