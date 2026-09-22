package com.info.platform.interfaces.common;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.common.JobLogPage;
import com.info.platform.application.common.JobLogQueryService;
import com.info.platform.application.common.JobLogView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * JobLogController 切片测试（T33）：standalone MockMvc + mock {@link JobLogQueryService}，验证 GET 列表/过滤/分页的
 * HTTP/JSON 编排与参数透传。JWT 鉴权由 {@code JwtAuthFilter} 在生产侧处理（独立测试覆盖），本切片不涉及。
 */
class JobLogControllerTest {

    private MockMvc mockMvc;
    private JobLogQueryService service;

    @BeforeEach
    void setUp() {
        service = mock(JobLogQueryService.class);
        JobLogController controller = new JobLogController(service);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private JobLogView view(long id, String jobName, String status, Long duration, String error) {
        return new JobLogView(
                id,
                jobName,
                Instant.parse("2026-09-22T01:00:00Z"),
                Instant.parse("2026-09-22T01:00:01Z"),
                status,
                duration,
                5,
                0,
                error);
    }

    @Test
    void list_defaultNoParams_returnsPage() throws Exception {
        // Arrange：一页不足 20 条 → nextCursor=null
        JobLogPage page =
                new JobLogPage(List.of(view(10L, "PolicyFetchJob", "SUCCESS", 1000L, null)), null);
        when(service.list(isNull(), isNull())).thenReturn(page);

        // Act + Assert
        mockMvc.perform(get("/api/v1/job-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].jobName").value("PolicyFetchJob"))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].durationMillis").value(1000))
                .andExpect(jsonPath("$.data.items[0].processedCount").value(5))
                .andExpect(jsonPath("$.data.items[0].errorMessage").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void list_withJobNameFilter_passesFilterParam() throws Exception {
        JobLogPage page =
                new JobLogPage(
                        List.of(view(11L, "AnomalyDetectionJob", "FAILED", 2000L, "boom")), null);
        when(service.list(eq("AnomalyDetectionJob"), isNull())).thenReturn(page);

        mockMvc.perform(get("/api/v1/job-logs").param("jobName", "AnomalyDetectionJob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].jobName").value("AnomalyDetectionJob"))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].errorMessage").value("boom"));
        verify(service).list("AnomalyDetectionJob", null);
    }

    @Test
    void list_withCursor_passesCursorParamAndReturnsNextCursor() throws Exception {
        // Arrange：满页 → nextCursor=末条 id=20
        JobLogPage page =
                new JobLogPage(
                        List.of(
                                view(21L, "PushRetryJob", "SUCCESS", 50L, null),
                                view(20L, "PushRetryJob", "SUCCESS", 60L, null)),
                        20L);
        when(service.list(eq("PushRetryJob"), eq(10L))).thenReturn(page);

        // Act + Assert：带 cursor 翻页，返回 nextCursor
        mockMvc.perform(
                        get("/api/v1/job-logs")
                                .param("jobName", "PushRetryJob")
                                .param("cursor", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.nextCursor").value(20));
        verify(service).list("PushRetryJob", 10L);
    }

    @Test
    void list_emptyResult_returnsEmptyItemsAndNoNextCursor() throws Exception {
        when(service.list(isNull(), isNull())).thenReturn(new JobLogPage(List.of(), null));

        mockMvc.perform(get("/api/v1/job-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void list_statusVariants_renderAllStates() throws Exception {
        // Arrange：SUCCESS / FAILED / STARTED 三态同表
        JobLogPage page =
                new JobLogPage(
                        List.of(
                                view(1L, "PolicyFetchJob", "SUCCESS", 100L, null),
                                view(2L, "AnomalyDetectionJob", "FAILED", 200L, "err"),
                                new JobLogView(
                                        3L,
                                        "DailyRecommendationJob",
                                        Instant.parse("2026-09-22T01:00:00Z"),
                                        null,
                                        "STARTED",
                                        null,
                                        0,
                                        0,
                                        null)),
                        null);
        when(service.list(isNull(), isNull())).thenReturn(page);

        mockMvc.perform(get("/api/v1/job-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[2].status").value("STARTED"))
                .andExpect(jsonPath("$.data.items[2].durationMillis").doesNotExist());
    }
}
