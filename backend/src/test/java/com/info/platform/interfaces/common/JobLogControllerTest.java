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
import com.info.platform.application.common.JobLogPagedView;
import com.info.platform.application.common.JobLogQueryService;
import com.info.platform.application.common.JobLogView;
import com.info.platform.domain.common.JobExecutionStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * JobLogController 切片测试（T33 + M9 T61 页码模式）：standalone MockMvc + mock {@link JobLogQueryService}，验证
 * GET 列表/过滤/分页（游标与页码双模式）的 HTTP/JSON 编排与参数透传。JWT 鉴权由 {@code JwtAuthFilter} 在生产侧处理（独立测试覆盖），本切片不涉及。
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

    // ==================== M9 页码模式契约（T61，REQ-20260925-06 方案 §4.2 / ADR-0035） ====================
    // 修前红锚点：实现前这些用例必须红（旧控制器忽略 page/size/status → 200 游标形态）；实现后转绿。

    @Test
    void pageMode_pageWithCursor_mutexRejected400() throws Exception {
        // page 与 cursor 互斥（双模式防呆）→ 400/2001
        mockMvc.perform(get("/api/v1/job-logs").param("page", "1").param("cursor", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void pageMode_statusWithoutPage_rejected400() throws Exception {
        // status 仅页码模式可用，缺 page → 400/2001（msg 注明缺 page）
        mockMvc.perform(get("/api/v1/job-logs").param("status", "FAILED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    void pageMode_sizeOverLimit_rejected400() throws Exception {
        // size 1~50，超限 400 拒绝不截断（ADR-0035）
        mockMvc.perform(get("/api/v1/job-logs").param("page", "1").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void pageMode_unknownStatus_rejected400() throws Exception {
        // 非法 status 值 → 400/2001，msg「未知 status: xxx」（显式转 BusinessException）
        mockMvc.perform(get("/api/v1/job-logs").param("page", "1").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("BOGUS")));
    }

    @Test
    void pageMode_returnsTotalAndEchoedPageSize() throws Exception {
        // Arrange：页码模式 + status 筛选走 listPaged（jobName/status 透传）
        when(service.listPaged(isNull(), eq(JobExecutionStatus.FAILED), eq(2), eq(10)))
                .thenReturn(
                        new JobLogPagedView(
                                List.of(view(30L, "PolicyFetchJob", "FAILED", 2000L, "boom")),
                                7L,
                                2,
                                10));

        // Act + Assert：页码模式响应形态：{items[], total, page, size}，无 nextCursor（两 record 不混装）
        mockMvc.perform(
                        get("/api/v1/job-logs")
                                .param("status", "FAILED")
                                .param("page", "2")
                                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(30))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].errorMessage").value("boom"))
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void pageMode_jobNameAndStatusCombo_passesBothFilters() throws Exception {
        // Arrange：jobName + status 组合（AND 语义）透传 service
        when(service.listPaged(eq("PolicyFetchJob"), eq(JobExecutionStatus.STARTED), eq(1), eq(20)))
                .thenReturn(new JobLogPagedView(List.of(), 0L, 1, 20));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/job-logs")
                                .param("jobName", "PolicyFetchJob")
                                .param("status", "STARTED")
                                .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
        verify(service).listPaged("PolicyFetchJob", JobExecutionStatus.STARTED, 1, 20);
    }

    @Test
    void pageMode_blankStatus_treatedAsNoFilter() throws Exception {
        // Arrange：status= （blank）= 不过滤，且不触发「缺 page」400（blank 视为缺席）
        when(service.listPaged(isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(new JobLogPagedView(List.of(), 5L, 1, 20));

        // Act + Assert
        mockMvc.perform(get("/api/v1/job-logs").param("status", " ").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5));
        verify(service).listPaged(null, null, 1, 20);
    }

    @Test
    void pageMode_outOfRangePage_returns200EmptyListWithEcho() throws Exception {
        // Arrange：越界页（total=7、page=99）→ 200 + 空列表 + 如实回显（ADR-0035 裁决）
        when(service.listPaged(isNull(), isNull(), eq(99), eq(20)))
                .thenReturn(new JobLogPagedView(List.of(), 7L, 99, 20));

        // Act + Assert
        mockMvc.perform(get("/api/v1/job-logs").param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.page").value(99))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void pageMode_cursorModeUntouched_whenOnlyCursorParamsPresent() throws Exception {
        // Arrange：page/size/status 均缺席 → 纯游标路径（既有行为，字节级不动回归）
        when(service.list(eq("PushRetryJob"), eq(10L)))
                .thenReturn(
                        new JobLogPage(
                                List.of(view(9L, "PushRetryJob", "SUCCESS", 10L, null)), null));

        // Act + Assert：仍返回游标形态（items + nextCursor 缺席即 null）
        mockMvc.perform(
                        get("/api/v1/job-logs")
                                .param("jobName", "PushRetryJob")
                                .param("cursor", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.page").doesNotExist());
        verify(service).list("PushRetryJob", 10L);
    }
}
