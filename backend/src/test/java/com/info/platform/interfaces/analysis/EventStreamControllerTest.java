package com.info.platform.interfaces.analysis;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.info.platform.application.analysis.EventStreamPageView;
import com.info.platform.application.analysis.EventStreamQueryService;
import com.info.platform.application.analysis.EventStreamView;
import com.info.platform.application.analysis.ImpactChainService;
import com.info.platform.application.analysis.ImpactChainView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * EventStreamController 切片测试（T127，方案 §4.8）：路由 /api/v1/events、六参数透传、Result 包装、卡片字段线格式、 30079
 * 错误映射（standalone MockMvc + service mock；Jackson 注册 JavaTimeModule 对齐 Boot ISO-8601 线格式）。
 */
class EventStreamControllerTest {

    private MockMvc mockMvc;
    private EventStreamQueryService queryService;
    private ImpactChainService impactChainService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        queryService = mock(EventStreamQueryService.class);
        impactChainService = mock(ImpactChainService.class);
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new EventStreamController(queryService, impactChainService))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void list_returnsWrappedCards() throws Exception {
        Instant eventTime = Instant.parse("2026-09-22T07:30:00Z");
        when(queryService.list(
                        eq("POLICY_RELEASE"),
                        eq("银行"),
                        eq("HIGH"),
                        eq("BULLISH"),
                        eq(null),
                        eq(100L),
                        eq(20)))
                .thenReturn(
                        new EventStreamView(
                                42L,
                                List.of(
                                        new EventStreamView.EventCardView(
                                                9L,
                                                com.info.platform.domain.analysis.EventType
                                                        .POLICY_RELEASE,
                                                "央行降准释放流动性",
                                                List.of("银行", "非银金融"),
                                                com.info.platform.domain.analysis.Direction.BULLISH,
                                                com.info.platform.domain.analysis.Importance.HIGH,
                                                List.of(
                                                        new EventStreamView.FigureView(
                                                                "存款准备金率", "0.5", "pct")),
                                                List.of(
                                                        new EventStreamView.SubjectView(
                                                                "SZ000001", "平安银行", "银行")),
                                                "下调存款准备金率 0.5 个百分点",
                                                1009L,
                                                "央行宣布降准",
                                                "https://example.com/n/9",
                                                eventTime)),
                                3L));

        mockMvc.perform(
                        get("/api/v1/events")
                                .param("type", "POLICY_RELEASE")
                                .param("industry", "银行")
                                .param("importance", "HIGH")
                                .param("direction", "BULLISH")
                                .param("beforeId", "100")
                                .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.nextBeforeId").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].eventType").value("POLICY_RELEASE"))
                .andExpect(jsonPath("$.data.items[0].summary").value("央行降准释放流动性"))
                .andExpect(jsonPath("$.data.items[0].industries[0]").value("银行"))
                .andExpect(jsonPath("$.data.items[0].direction").value("BULLISH"))
                .andExpect(jsonPath("$.data.items[0].importance").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].figures[0].label").value("存款准备金率"))
                .andExpect(jsonPath("$.data.items[0].subjects[0].code").value("SZ000001"))
                .andExpect(jsonPath("$.data.items[0].quote").value("下调存款准备金率 0.5 个百分点"))
                .andExpect(jsonPath("$.data.items[0].newsId").value(1009))
                .andExpect(jsonPath("$.data.items[0].newsTitle").value("央行宣布降准"))
                .andExpect(jsonPath("$.data.items[0].newsUrl").value("https://example.com/n/9"))
                .andExpect(jsonPath("$.data.items[0].eventTime").value("2026-09-22T07:30:00Z"));
    }

    @Test
    void list_paramsAllOptional() throws Exception {
        when(queryService.list(eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(new EventStreamView(0L, List.of(), null));

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void list_invalidFilter_400_30079() throws Exception {
        when(queryService.list(
                        eq("NOT_A_TYPE"), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.EVENT_FILTER_INVALID, "type: 须为 9 类事件枚举，当前值 NOT_A_TYPE"));

        mockMvc.perform(get("/api/v1/events").param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30079))
                .andExpect(jsonPath("$.msg").value("type: 须为 9 类事件枚举，当前值 NOT_A_TYPE"));
    }

    // ---- M29 P1-01 回归（方案 §5.4）：market 参数两模式透传与非法 400 ----

    @Test
    void list_marketParam_passedThrough_cursorMode() throws Exception {
        // Arrange/Act：游标模式 market=HK 透传服务层
        when(queryService.list(eq(null), eq(null), eq(null), eq(null), eq("HK"), eq(null), eq(null)))
                .thenReturn(new EventStreamView(6L, List.of(), null));

        mockMvc.perform(get("/api/v1/events").param("market", "HK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(6));
    }

    @Test
    void list_marketParam_passedThrough_pageMode_withIndustryFilterGroups() throws Exception {
        // Arrange：页码模式 market=US + 响应 industryFilterGroups（前端 Events.tsx 消费契约——分组字段线格式）
        when(queryService.listPaged(eq(null), eq(null), eq(null), eq(null), eq("US"), eq(1), eq(20)))
                .thenReturn(
                        new EventStreamPageView(
                                21L,
                                List.of(),
                                1,
                                20,
                                EventStreamPageView.INDUSTRY_FILTER_GROUPS));

        // Act/Assert：market 参数透传 + 分组结构（三市场组、枚举数组）序列化可见
        mockMvc.perform(get("/api/v1/events").param("market", "US").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.industryFilterGroups[0].market").value("A_SHARE"))
                .andExpect(jsonPath("$.data.industryFilterGroups[0].industries[0]").value("农林牧渔"))
                .andExpect(jsonPath("$.data.industryFilterGroups[1].market").value("HK"))
                .andExpect(
                        jsonPath("$.data.industryFilterGroups[1].industries.length()").value(31))
                .andExpect(jsonPath("$.data.industryFilterGroups[2].market").value("US"))
                .andExpect(
                        jsonPath("$.data.industryFilterGroups[2].industries.length()").value(40));
    }

    @Test
    void list_invalidMarket_400_30079() throws Exception {
        // Arrange：服务层抛 market 字段级 30079（market: 须为 A_SHARE / HK / US）
        when(queryService.list(eq(null), eq(null), eq(null), eq(null), eq("JP"), eq(null), eq(null)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.EVENT_FILTER_INVALID,
                                "market: 须为 A_SHARE / HK / US，当前值 JP"));

        // Act/Assert
        mockMvc.perform(get("/api/v1/events").param("market", "JP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30079))
                .andExpect(jsonPath("$.msg").value("market: 须为 A_SHARE / HK / US，当前值 JP"));
    }

    // ---- T220（M25 V3.0）：page/size 页码模式（M9 PageQuery 双模式分派，beforeId 游标保留兼容）----

    private static EventStreamView.EventCardView pageCard(long id) {
        return new EventStreamView.EventCardView(
                id,
                com.info.platform.domain.analysis.EventType.POLICY_RELEASE,
                "央行降准释放流动性",
                List.of("银行"),
                com.info.platform.domain.analysis.Direction.BULLISH,
                com.info.platform.domain.analysis.Importance.HIGH,
                List.of(),
                List.of(),
                null,
                1000L + id,
                "央行宣布降准",
                "https://example.com/n/" + id,
                Instant.parse("2026-09-22T07:30:00Z"));
    }

    @Test
    void list_pageMode_returnsPagedViewWithoutCursorFields() throws Exception {
        // Arrange：page 出现即页码模式（{total, items, page, size}，无 nextBeforeId）
        when(queryService.listPaged(eq(null), eq(null), eq(null), eq(null), eq(null), eq(2), eq(5)))
                .thenReturn(new EventStreamPageView(42L, List.of(pageCard(9L)), 2, 5, null));

        // Act + Assert
        mockMvc.perform(get("/api/v1/events").param("page", "2").param("size", "5"))
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
        // Arrange：四维筛选与页码模式正交（页码模式下同样可用）
        when(queryService.listPaged(
                        eq("POLICY_RELEASE"),
                        eq("银行"),
                        eq("HIGH"),
                        eq("BULLISH"),
                        eq(null),
                        eq(1),
                        eq(20)))
                .thenReturn(new EventStreamPageView(1L, List.of(), 1, 20, null));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/events")
                                .param("type", "POLICY_RELEASE")
                                .param("industry", "银行")
                                .param("importance", "HIGH")
                                .param("direction", "BULLISH")
                                .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20)); // size 缺省 20（PageQuery.DEFAULT_SIZE）
    }

    @Test
    void list_pageWithBeforeId_mutuallyExclusive_400() throws Exception {
        // Arrange/Act/Assert：page 与 beforeId 互斥（2001，M9 契约确定性）
        mockMvc.perform(get("/api/v1/events").param("page", "1").param("beforeId", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("page 与 cursor 互斥，只能二选一"));
    }

    @Test
    void list_sizeWithoutPage_400() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("缺少 page 参数（size 仅页码模式可用）"));
    }

    @Test
    void list_limitWithPage_400() throws Exception {
        // limit 为游标模式专属（页码模式请用 size——M9/资讯库同例）
        mockMvc.perform(get("/api/v1/events").param("page", "1").param("limit", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("limit 仅游标模式可用（页码模式请使用 size）"));
    }

    @Test
    void list_pageModeBounds_rejectedNotTruncated() throws Exception {
        // 越界拒绝不截断（page<1 / page 超上限 / size<1 / size>50）
        mockMvc.perform(get("/api/v1/events").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/events").param("page", "1000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/events").param("page", "1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/events").param("page", "1").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_cursorMode_regression_pageAbsent() throws Exception {
        // 页码+游标模式并存回归：page 缺席走既有游标路径（字节级不动，nextBeforeId 照常）
        when(queryService.list(eq(null), eq(null), eq(null), eq(null), eq(null), eq(100L), eq(null)))
                .thenReturn(new EventStreamView(42L, List.of(pageCard(9L)), 9L));

        mockMvc.perform(get("/api/v1/events").param("beforeId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextBeforeId").value(9))
                .andExpect(jsonPath("$.data.page").doesNotExist());
    }

    // ---- T144（M17）：事件详情影响链区块端点 GET /api/v1/events/{id}/impact-chains ----

    @Test
    void impactChains_returnsChainsWithBasisTrace() throws Exception {
        when(impactChainService.chainsForEvent(9L))
                .thenReturn(
                        new ImpactChainView(
                                9L,
                                "HIGH",
                                "CACHED",
                                List.of(
                                        new ImpactChainView.ChainItemView(
                                                1L,
                                                "银行",
                                                "BULLISH",
                                                "流动性宽松降低银行负债成本，信贷投放预期改善",
                                                mapper.readTree(
                                                        "{\"newsId\":100,\"signalNewsIds\":[100],\"quote\":\"降准\"}"),
                                                "POLICY_MONETARY",
                                                "AUTO",
                                                "TEMPLATE")),
                                "AI 分析仅供参考"));

        mockMvc.perform(get("/api/v1/events/9/impact-chains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.eventId").value(9))
                .andExpect(jsonPath("$.data.importance").value("HIGH"))
                .andExpect(jsonPath("$.data.eligibility").value("CACHED"))
                .andExpect(jsonPath("$.data.chains[0].industry").value("银行"))
                .andExpect(jsonPath("$.data.chains[0].direction").value("BULLISH"))
                .andExpect(jsonPath("$.data.chains[0].logicChain").isNotEmpty())
                .andExpect(jsonPath("$.data.chains[0].basis.newsId").value(100))
                .andExpect(jsonPath("$.data.chains[0].templateKey").value("POLICY_MONETARY"))
                .andExpect(jsonPath("$.data.chains[0].cacheState").value("AUTO"))
                .andExpect(jsonPath("$.data.chains[0].genMethod").value("TEMPLATE"))
                .andExpect(jsonPath("$.data.disclaimer").value("AI 分析仅供参考"));
    }

    @Test
    void impactChains_lowEvent_emptyEligibility() throws Exception {
        when(impactChainService.chainsForEvent(10L))
                .thenReturn(new ImpactChainView(10L, "LOW", "LOW_SKIPPED", List.of(), "AI 分析仅供参考"));

        mockMvc.perform(get("/api/v1/events/10/impact-chains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligibility").value("LOW_SKIPPED"))
                .andExpect(jsonPath("$.data.chains").isEmpty());
    }

    @Test
    void impactChains_eventNotFound_404_30083() throws Exception {
        when(impactChainService.chainsForEvent(404L))
                .thenThrow(new BusinessException(ErrorCode.EVENT_NOT_FOUND, "事件不存在: 404"));

        mockMvc.perform(get("/api/v1/events/404/impact-chains"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30083));
    }
}
