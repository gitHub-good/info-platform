package com.info.platform.interfaces.feed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.feed.ConnectivityTestResultView;
import com.info.platform.application.feed.InfoSourceCardView;
import com.info.platform.application.feed.InfoSourcesListView;
import com.info.platform.application.feed.SourceRegistryService;
import com.info.platform.application.feed.SourceStatsView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * InfoSourceController 切片测试（T105，方案 §4.5）：路由/状态码/参数装配与错误码映射（30071~30075）， standalone MockMvc +
 * service mock。
 */
class InfoSourceControllerTest {

    private MockMvc mockMvc;
    private SourceRegistryService registry;

    @BeforeEach
    void setUp() {
        registry = mock(SourceRegistryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new InfoSourceController(registry))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static InfoSourceCardView card(String sourceCode) {
        return new InfoSourceCardView(
                7L,
                sourceCode,
                "测试源",
                "快讯",
                "rss",
                null,
                "https://example.com/rss.xml",
                null,
                15,
                true,
                false,
                false,
                new InfoSourceCardView.TodayCountersView(2, 0, 12, 3),
                InfoSourceCardView.PollStateSummaryView.EMPTY,
                "2026-09-22T08:00:00Z",
                "2026-09-22T08:00:00Z");
    }

    @Test
    void list_returnsGroupedView() throws Exception {
        when(registry.list())
                .thenReturn(
                        new InfoSourcesListView(
                                List.of(
                                        new InfoSourcesListView.CategoryGroup(
                                                "快讯", List.of(card("t105_a")))),
                                List.of(card("t105_archived"))));

        mockMvc.perform(get("/api/v1/info-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups[0].category").value("快讯"))
                .andExpect(jsonPath("$.data.groups[0].sources[0].sourceCode").value("t105_a"))
                .andExpect(jsonPath("$.data.archived[0].sourceCode").value("t105_archived"));
    }

    @Test
    void create_mapsPayloadToCommand_withConfigDomain() throws Exception {
        when(registry.create(any())).thenReturn(card("t105_new"));

        mockMvc.perform(
                        post("/api/v1/info-sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"金十数据","category":"快讯","adapterType":"json_api",
                                         "endpoint":"https://www.jin10.com/flash_newest.js","intervalMinutes":5,
                                         "config":{"listPath":"","stripPrefix":"var newest=",
                                           "itemMapping":[{"source":"title","target":"title"}],
                                           "cursorType":"ID","cursorField":"externalId"}}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceCode").value("t105_new"));

        ArgumentCaptor<SourceRegistryService.CreateCommand> captor =
                ArgumentCaptor.forClass(SourceRegistryService.CreateCommand.class);
        verify(registry).create(captor.capture());
        SourceRegistryService.CreateCommand command = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(command.adapterType())
                .isEqualTo(AdapterType.JSON_API);
        org.assertj.core.api.Assertions.assertThat(command.config().stripPrefix())
                .isEqualTo("var newest=");
        org.assertj.core.api.Assertions.assertThat(command.config().effectiveCursorType())
                .isEqualTo(CursorType.ID);
    }

    @Test
    void create_unknownAdapterType_30072() throws Exception {
        mockMvc.perform(
                        post("/api/v1/info-sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"X","category":"自建","adapterType":"not_a_type",
                                         "endpoint":"https://example.com","intervalMinutes":15}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30072));
    }

    @Test
    void create_htmlTemplateParsed_flowsToServiceWhitelist_30072() throws Exception {
        // html_template 是合法枚举（预留值）：解析透传，由服务层通道白名单拒绝（蓝图裁决 1）
        when(registry.create(any()))
                .thenThrow(new BusinessException(ErrorCode.INFO_SOURCE_CONFIG_INVALID));

        mockMvc.perform(
                        post("/api/v1/info-sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"X","category":"自建","adapterType":"html_template",
                                         "endpoint":"https://example.com","intervalMinutes":15}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30072));

        ArgumentCaptor<SourceRegistryService.CreateCommand> captor =
                ArgumentCaptor.forClass(SourceRegistryService.CreateCommand.class);
        verify(registry).create(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().adapterType())
                .isEqualTo(AdapterType.HTML_TEMPLATE);
    }

    @Test
    void create_robotsForbidden_30075() throws Exception {
        when(registry.create(any()))
                .thenThrow(new BusinessException(ErrorCode.INFO_SOURCE_ROBOTS_FORBIDDEN));

        mockMvc.perform(
                        post("/api/v1/info-sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"X","category":"自建","adapterType":"rss",
                                         "endpoint":"https://example.com","intervalMinutes":15}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30075));
    }

    @Test
    void patch_passesPartialFields_missingSource_30071() throws Exception {
        when(registry.update(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.INFO_SOURCE_NOT_FOUND));

        mockMvc.perform(
                        patch("/api/v1/info-sources/9")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"intervalMinutes\":30}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30071));
    }

    @Test
    void patch_passesAdapterTypeThrough_serviceRejectsChange_30072() throws Exception {
        when(registry.update(anyLong(), any()))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INFO_SOURCE_CONFIG_INVALID, "adapterType 不可变更"));

        mockMvc.perform(
                        patch("/api/v1/info-sources/9")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"adapterType\":\"json_api\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30072));
    }

    @Test
    void enableDisable_callService() throws Exception {
        when(registry.changeEnabled(9L, true)).thenReturn(card("t105_e"));

        mockMvc.perform(post("/api/v1/info-sources/9/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        verify(registry).changeEnabled(9L, true);

        InfoSourceCardView disabled =
                new InfoSourceCardView(
                        7L,
                        "t105_d",
                        "停用源",
                        "自建",
                        "rss",
                        null,
                        "https://example.com/d.xml",
                        null,
                        15,
                        false,
                        false,
                        false,
                        InfoSourceCardView.TodayCountersView.EMPTY,
                        InfoSourceCardView.PollStateSummaryView.EMPTY,
                        "2026-09-22T08:00:00Z",
                        "2026-09-22T08:00:00Z");
        when(registry.changeEnabled(9L, false)).thenReturn(disabled);
        mockMvc.perform(post("/api/v1/info-sources/9/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        verify(registry).changeEnabled(9L, false);
    }

    @Test
    void delete_presetSource_30073() throws Exception {
        when(registry.archive(9L))
                .thenThrow(new BusinessException(ErrorCode.INFO_SOURCE_PRESET_DELETE_FORBIDDEN));

        mockMvc.perform(delete("/api/v1/info-sources/9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(30073));
    }

    @Test
    void delete_genericSource_returnsArchivedCard() throws Exception {
        InfoSourceCardView archived = card("t105_archived");
        when(registry.archive(9L)).thenReturn(archived);

        mockMvc.perform(delete("/api/v1/info-sources/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceCode").value("t105_archived"))
                .andExpect(jsonPath("$.data.deleted").value(false));
    }

    @Test
    void restore_returnsRestoredCard() throws Exception {
        when(registry.restore(9L)).thenReturn(card("t105_restored"));

        mockMvc.perform(post("/api/v1/info-sources/9/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceCode").value("t105_restored"));
    }

    @Test
    void connectivityTest_returnsDiagnosticsBody() throws Exception {
        when(registry.connectivityTest(9L))
                .thenReturn(
                        new ConnectivityTestResultView(
                                true,
                                true,
                                412L,
                                20,
                                null,
                                List.of(
                                        new ConnectivityTestResultView.SampleItem(
                                                "样本",
                                                "https://example.com/n",
                                                "2026-09-22T09:31:00Z"))));

        mockMvc.perform(post("/api/v1/info-sources/9/connectivity-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reachable").value(true))
                .andExpect(jsonPath("$.data.robotsAllowed").value(true))
                .andExpect(jsonPath("$.data.latencyMillis").value(412))
                .andExpect(jsonPath("$.data.parsedCount").value(20))
                .andExpect(jsonPath("$.data.sampleItems[0].title").value("样本"));
    }

    @Test
    void poll_accepted202_withSourceReceipt_inFlight30074() throws Exception {
        com.info.platform.domain.feed.InfoSource pollSource =
                com.info.platform.domain.feed.InfoSource.create(
                        "t105_p",
                        "轮询源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/p.xml",
                        SourceConfig.empty(),
                        5,
                        true,
                        false);
        pollSource.assignPersisted(
                7L,
                java.time.Instant.parse("2026-09-22T08:00:00Z"),
                java.time.Instant.parse("2026-09-22T08:00:00Z"));
        when(registry.submitPoll(9L)).thenReturn(pollSource);

        mockMvc.perform(post("/api/v1/info-sources/9/poll"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceId").value(7))
                .andExpect(jsonPath("$.data.sourceCode").value("t105_p"));

        when(registry.submitPoll(9L))
                .thenThrow(new BusinessException(ErrorCode.INFO_SOURCE_POLL_IN_FLIGHT));
        mockMvc.perform(post("/api/v1/info-sources/9/poll"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30074));
    }

    @Test
    void stats_daysDefault7_andBoundsValidated() throws Exception {
        when(registry.stats(eq(7)))
                .thenReturn(
                        new SourceStatsView(
                                7,
                                "2026-09-16",
                                List.of(),
                                List.of(),
                                new SourceStatsView.Latency(1000L, 3000L, 10)));

        mockMvc.perform(get("/api/v1/info-sources/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.latency.p50Millis").value(1000))
                .andExpect(jsonPath("$.data.latency.p90Millis").value(3000));
        verify(registry).stats(7);

        mockMvc.perform(get("/api/v1/info-sources/stats").param("days", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/info-sources/stats").param("days", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void stats_passesResolvedDays() throws Exception {
        when(registry.stats(eq(30)))
                .thenReturn(
                        new SourceStatsView(
                                30,
                                "2026-08-24",
                                List.of(),
                                List.of(),
                                new SourceStatsView.Latency(null, null, 0)));

        mockMvc.perform(get("/api/v1/info-sources/stats").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30));
        verify(registry).stats(30);
    }
}
