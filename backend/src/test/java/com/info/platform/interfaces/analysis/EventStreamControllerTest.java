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
        when(queryService.list(eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(new EventStreamView(0L, List.of(), null));

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void list_invalidFilter_400_30079() throws Exception {
        when(queryService.list(eq("NOT_A_TYPE"), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.EVENT_FILTER_INVALID, "type: 须为 9 类事件枚举，当前值 NOT_A_TYPE"));

        mockMvc.perform(get("/api/v1/events").param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30079))
                .andExpect(jsonPath("$.msg").value("type: 须为 9 类事件枚举，当前值 NOT_A_TYPE"));
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
