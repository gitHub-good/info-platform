package com.info.platform.interfaces.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.common.JobLogPage;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.JobDetailView;
import com.info.platform.application.jobrun.JobCenterFacade.JobView;
import com.info.platform.application.jobrun.JobCenterFacade.JobsView;
import com.info.platform.application.jobrun.JobCenterFacade.LastExecutionView;
import com.info.platform.application.jobrun.JobCenterFacade.TriggerResult;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * JobController 切片测试（T37，契约 §4.4.3）：总览/详情形状 + PATCH 透传 + 手动触发 202 受理 + 错误码映射 （30062→404 / 30063→409
 * / 30065→409 / 2001→400）。
 *
 * <p>对齐 DataSourceConfigControllerTest 模式：standaloneSetup 独立 MockMvc，应用端口 Mockito mock（JWT 保护由生产
 * 过滤器承担，此处不重复测）。
 */
class JobControllerTest {

    private MockMvc mockMvc;
    private JobCenterFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(JobCenterFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new JobController(facade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static JobView jobView() {
        return new JobView(
                "POLICY_FETCH",
                "PolicyFetchJob",
                "政策抓取",
                "抓取 gov.cn/zhengce 最近政策，去重后标注行业入库",
                "FIXED_DELAY",
                3600000L,
                null,
                "",
                true,
                false,
                new LastExecutionView(
                        "SUCCESS", "2026-09-22T03:00:00Z", "2026-09-22T03:00:01Z", 1200L),
                "2026-09-22T04:00:01Z",
                "2026-09-22T01:00:00Z",
                Map.of("enabled", "LIVE", "intervalMillis", "LIVE_NEXT_CYCLE"));
    }

    @Test
    void list_returns200WithJobRows() throws Exception {
        when(facade.view()).thenReturn(new JobsView(List.of(jobView())));

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobs[0].jobKey").value("POLICY_FETCH"))
                .andExpect(jsonPath("$.data.jobs[0].jobName").value("PolicyFetchJob"))
                .andExpect(jsonPath("$.data.jobs[0].name").value("政策抓取"))
                .andExpect(jsonPath("$.data.jobs[0].scheduleType").value("FIXED_DELAY"))
                .andExpect(jsonPath("$.data.jobs[0].intervalMillis").value(3600000))
                .andExpect(jsonPath("$.data.jobs[0].enabled").value(true))
                .andExpect(jsonPath("$.data.jobs[0].running").value(false))
                .andExpect(jsonPath("$.data.jobs[0].lastExecution.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.jobs[0].lastExecution.durationMillis").value(1200))
                .andExpect(jsonPath("$.data.jobs[0].nextExecutionTime").isNotEmpty())
                .andExpect(jsonPath("$.data.jobs[0].effectiveModes.enabled").value("LIVE"));
    }

    @Test
    void detail_returns200WithRecentExecutions() throws Exception {
        when(facade.detail("POLICY_FETCH"))
                .thenReturn(new JobDetailView(jobView(), new JobLogPage(List.of(), null).items()));

        mockMvc.perform(get("/api/v1/jobs/POLICY_FETCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.jobKey").value("POLICY_FETCH"))
                .andExpect(jsonPath("$.data.recentExecutions").isArray());
    }

    @Test
    void detail_unknownJob_returns404With30062() throws Exception {
        when(facade.detail("NOPE"))
                .thenThrow(new BusinessException(ErrorCode.JOB_NOT_FOUND, "任务不存在: NOPE"));

        mockMvc.perform(get("/api/v1/jobs/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30062));
    }

    @Test
    void update_passesPatchBodyAndReturnsView() throws Exception {
        when(facade.update(any(), any())).thenReturn(jobView());

        mockMvc.perform(
                        patch("/api/v1/jobs/POLICY_FETCH")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"enabled\":true,\"intervalMillis\":7200000,"
                                                + "\"expectedUpdatedAt\":\"2026-09-22T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobKey").value("POLICY_FETCH"));
    }

    @Test
    void update_validationFailure_returns400With2001() throws Exception {
        when(facade.update(any(), any()))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PARAM_INVALID, "cron: 格式不正确（6 段 Spring cron）"));

        mockMvc.perform(
                        patch("/api/v1/jobs/POLICY_FETCH")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cron\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("cron: 格式不正确（6 段 Spring cron）"));
    }

    @Test
    void update_concurrentConflict_returns409With30065() throws Exception {
        when(facade.update(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT, "配置已被并发修改"));

        mockMvc.perform(
                        patch("/api/v1/jobs/POLICY_FETCH")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }

    @Test
    void run_acceptsWith202AndExecutionId() throws Exception {
        when(facade.trigger("PUSH_RETRY")).thenReturn(new TriggerResult(123L, "STARTED"));

        mockMvc.perform(post("/api/v1/jobs/PUSH_RETRY/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.executionId").value(123))
                .andExpect(jsonPath("$.data.status").value("STARTED"));
    }

    @Test
    void run_alreadyRunning_returns409With30063() throws Exception {
        when(facade.trigger("PUSH_RETRY"))
                .thenThrow(new BusinessException(ErrorCode.JOB_ALREADY_RUNNING, "任务正在运行"));

        mockMvc.perform(post("/api/v1/jobs/PUSH_RETRY/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30063));
    }
}
