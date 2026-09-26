package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.ImpactCacheState;
import com.info.platform.domain.analysis.ImpactChainRepository;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryImpactChain;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 影响链生成服务单测（M17 T144，REQ 故事 3 场景 1/2/4/5）：HIGH 自动（AUTO 缓存态）/ MEDIUM 首次展开按需生成并缓存（重复展开不重复生成）/ LOW
 * 不生成（空态）/ 白名单零新增事实（全事件类型 × 三方向渲染行业 ⊆ 申万 31）/ 依据回溯 JSON 含信号来源条目 id 集 / 事件不存在 30083。
 */
class ImpactChainServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    private static final long EVENT_ID = 9L;

    private ImpactChainRepository chainRepository;

    private EventItemRepository eventRepository;

    private ImpactChainService service;

    @BeforeEach
    void setUp() {
        chainRepository = mock(ImpactChainRepository.class);
        eventRepository = mock(EventItemRepository.class);
        service =
                new ImpactChainService(
                        chainRepository,
                        eventRepository,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("LOW 事件：不生成不落库（空态语义）")
    void chainsForEvent_lowEvent_skipsGeneration() {
        stubEvent(persistedEvent(Importance.LOW, EventType.OTHER, Direction.BULLISH));

        ImpactChainView view = service.chainsForEvent(EVENT_ID);

        assertThat(view.eligibility()).isEqualTo("LOW_SKIPPED");
        assertThat(view.chains()).isEmpty();
        verify(chainRepository, never()).replaceForEvent(eq(EVENT_ID), anyList());
    }

    @Test
    @DisplayName("MEDIUM 事件：首次展开按需生成（ON_DEMAND 缓存态），再次展开读缓存不重复生成")
    void chainsForEvent_mediumGeneratesOnDemandThenCached() {
        EventItem event =
                persistedEvent(Importance.MEDIUM, EventType.EARNINGS_FORECAST, Direction.BULLISH);
        stubEvent(event);
        when(chainRepository.findByEventId(EVENT_ID))
                .thenReturn(List.of())
                .thenReturn(cachedRow(event));

        ImpactChainView first = service.chainsForEvent(EVENT_ID);
        ImpactChainView second = service.chainsForEvent(EVENT_ID);

        assertThat(first.eligibility()).isEqualTo("ON_DEMAND");
        assertThat(first.chains()).isNotEmpty();
        assertThat(first.chains().get(0).cacheState()).isEqualTo("ON_DEMAND");
        assertThat(second.eligibility()).isEqualTo("CACHED");
        verify(chainRepository, times(1)).replaceForEvent(eq(EVENT_ID), anyList());
    }

    @Test
    @DisplayName("HIGH 事件：缓存缺位自愈生成（AUTO 缓存态——L2 落库自动生成的查询侧兜底）")
    void chainsForEvent_highMissing_selfHealsAsAuto() {
        stubEvent(persistedEvent(Importance.HIGH, EventType.POLICY_RELEASE, Direction.BULLISH));
        when(chainRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

        ImpactChainView view = service.chainsForEvent(EVENT_ID);

        assertThat(view.eligibility()).isEqualTo("AUTO");
        assertThat(view.chains()).isNotEmpty();
        assertThat(capturedChains())
                .allSatisfy(
                        chain ->
                                assertThat(chain.getCacheState()).isEqualTo(ImpactCacheState.AUTO));
    }

    @Test
    @DisplayName("事件不存在：30083")
    void chainsForEvent_eventNotFound_throws30083() {
        when(eventRepository.findByIds(List.of(EVENT_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.chainsForEvent(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.EVENT_NOT_FOUND));
    }

    @Test
    @DisplayName("白名单零新增事实：全事件类型 × 三方向渲染行业 ⊆ 申万 31 白名单")
    void generateFor_allTypesAndDirections_industriesWithinSwWhitelist() {
        for (EventType type : EventType.values()) {
            for (Direction direction : Direction.values()) {
                service.generateFor(
                        persistedEvent(Importance.HIGH, type, direction), ImpactCacheState.AUTO);
                assertThat(capturedChains())
                        .as("类型 %s 方向 %s 应有产出且全在白名单", type, direction)
                        .isNotEmpty()
                        .allSatisfy(
                                chain ->
                                        assertThat(
                                                        IndustryCategory.isSwIndustry(
                                                                chain.getIndustry()))
                                                .as("类型 %s 行业 %s 越界", type, chain.getIndustry())
                                                .isTrue());
            }
        }
    }

    @Test
    @DisplayName("依据回溯：basis JSON 含 newsId/信号来源条目 id 集/事件类型/引用")
    void generateFor_basisCarriesSignalTrace() throws Exception {
        service.generateFor(
                EventItem.reconstruct(
                        EVENT_ID,
                        100L,
                        EventType.POLICY_RELEASE,
                        "央行宣布降准0.5个百分点",
                        List.of("银行"),
                        Direction.BULLISH,
                        Importance.HIGH,
                        List.of(),
                        List.of(),
                        "原文引用片段",
                        NOW,
                        "2026-09-22",
                        "v1.0",
                        NOW,
                        NOW),
                ImpactCacheState.AUTO);

        JsonNode basis = new ObjectMapper().readTree(capturedChains().get(0).getBasis());
        assertThat(basis.path("newsId").asLong()).isEqualTo(100L);
        assertThat(basis.path("signalNewsIds").toString()).contains("100");
        assertThat(basis.path("eventType").asText()).isEqualTo("POLICY_RELEASE");
        assertThat(basis.path("quote").asText()).isEqualTo("原文引用片段");
    }

    @Test
    @DisplayName("视图契约：disclaimer 固定 + 生成方式模板态标注（v1 纯规则无 LLM）")
    void chainsForEvent_viewContract() {
        stubEvent(
                persistedEvent(Importance.MEDIUM, EventType.EARNINGS_FORECAST, Direction.BULLISH));
        when(chainRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

        ImpactChainView view = service.chainsForEvent(EVENT_ID);

        assertThat(view.disclaimer()).isEqualTo("AI 分析仅供参考");
        assertThat(view.chains().get(0).genMethod()).isEqualTo("TEMPLATE");
        assertThat(view.chains().get(0).direction()).isEqualTo("BULLISH");
        assertThat(view.chains().get(0).logicChain()).isNotBlank();
    }

    // ---- 工具 ----

    private void stubEvent(EventItem event) {
        when(eventRepository.findByIds(List.of(EVENT_ID))).thenReturn(List.of(event));
    }

    private static EventItem persistedEvent(
            Importance importance, EventType type, Direction direction) {
        return EventItem.reconstruct(
                EVENT_ID,
                100L,
                type,
                type == EventType.POLICY_RELEASE ? "央行宣布降准0.5个百分点" : "示例事件摘要",
                List.of("食品饮料"),
                direction,
                importance,
                List.of(),
                List.of(),
                null,
                NOW,
                "2026-09-22",
                "v1.0",
                NOW,
                NOW);
    }

    private List<IndustryImpactChain> capturedChains() {
        ArgumentCaptor<List<IndustryImpactChain>> captor = ArgumentCaptor.forClass(List.class);
        verify(chainRepository, atLeastOnce()).replaceForEvent(eq(EVENT_ID), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private static List<IndustryImpactChain> cachedRow(EventItem event) {
        return List.of(
                IndustryImpactChain.reconstruct(
                        1L,
                        EVENT_ID,
                        "食品饮料",
                        event.getDirection(),
                        "业绩指引利好，食品饮料板块盈利预期相应调整",
                        "{\"newsId\":100}",
                        event.getEventType().name(),
                        ImpactCacheState.ON_DEMAND,
                        NOW,
                        NOW));
    }
}
