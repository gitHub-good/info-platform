package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 行业周报读与重试服务单测（M17 T145，沿 IndustryReportServiceTest 同构）：列表游标分页（lookahead 多读一行精确 hasMore）、limit 越界
 * 30076、详情 30084/周锚非法 30076、重试 FAILED 武装 + trigger 受理（未受理 disarm 回滚）、已 SUCCESS 30085。
 */
class IndustryWeeklyReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private static final String WEEK = "2026-09-21";

    private WeeklyReportRepository weeklyRepository;

    private IndustryWeeklyReportJob weeklyJob;

    private JobCenterFacade jobCenter;

    private IndustryWeeklyReportService service;

    @BeforeEach
    void setUp() {
        weeklyRepository = mock(WeeklyReportRepository.class);
        weeklyJob = mock(IndustryWeeklyReportJob.class);
        jobCenter = mock(JobCenterFacade.class);
        service =
                new IndustryWeeklyReportService(
                        weeklyRepository, weeklyJob, jobCenter, new ObjectMapper());
    }

    @Test
    @DisplayName("列表：lookahead 多读一行精确 hasMore，尾页 nextBeforeId=null")
    void list_lookaheadPagination() {
        when(weeklyRepository.findPage(null, 3))
                .thenReturn(List.of(report(WEEK), report("2026-09-14"), report("2026-09-07")));

        IndustryWeeklyReportListView view = service.list(null, 2);

        assertThat(view.reports()).hasSize(2);
        assertThat(view.reports().get(0).weekStart()).isEqualTo(WEEK);
        assertThat(view.nextBeforeId()).isEqualTo(view.reports().get(1).id());

        when(weeklyRepository.findPage(null, 3)).thenReturn(List.of(report(WEEK)));
        assertThat(service.list(null, 2).nextBeforeId()).isNull();
    }

    @Test
    @DisplayName("limit 越界 30076（越界拒绝不截断）")
    void list_limitInvalid_30076() {
        assertThatThrownBy(() -> service.list(null, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID));
        assertThatThrownBy(() -> service.list(null, 51)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("详情：content JSON 透出；不存在 30084；非周一锚 30076")
    void detail_contract() {
        when(weeklyRepository.findByWeekStart(WEEK))
                .thenReturn(
                        Optional.of(
                                IndustryWeeklyReport.success(
                                        WEEK,
                                        "{\"summary\":\"本周总结\",\"totalNews\":120,\"totalEvents\":30,\"narrativeDegraded\":false}",
                                        "[]",
                                        null,
                                        "v1.0",
                                        "trend-v1|heat-v1",
                                        NOW)));

        IndustryWeeklyReportDetailView view = service.detail(WEEK);

        assertThat(view.content()).isNotNull();
        assertThat(view.content().path("totalNews").asLong()).isEqualTo(120);

        when(weeklyRepository.findByWeekStart("2026-09-28")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("2026-09-28"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_WEEKLY_REPORT_NOT_FOUND));

        assertThatThrownBy(() -> service.detail("2026-09-22")) // 周二
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID));
    }

    @Test
    @DisplayName("重试 FAILED：武装指定周 + trigger 受理回执；trigger 未受理时 disarm 回滚")
    void retry_failedWeekly_armsAndTriggers() {
        when(weeklyRepository.findByWeekStart(WEEK))
                .thenReturn(Optional.of(IndustryWeeklyReport.failed(WEEK, "err", NOW)));
        when(jobCenter.trigger("INDUSTRY_WEEKLY_REPORT"))
                .thenReturn(new JobCenterFacade.TriggerResult(88L, "STARTED"));

        IndustryWeeklyReportService.RetryAcceptance acceptance = service.retry(WEEK);

        assertThat(acceptance.executionId()).isEqualTo(88L);
        verify(weeklyJob).armRetry(java.time.LocalDate.parse(WEEK));

        when(jobCenter.trigger("INDUSTRY_WEEKLY_REPORT"))
                .thenThrow(new RuntimeException("409 running"));
        assertThatThrownBy(() -> service.retry(WEEK)).isInstanceOf(RuntimeException.class);
        verify(weeklyJob).disarmRetry();
    }

    @Test
    @DisplayName("重试：已 SUCCESS 30085；不存在 30084")
    void retry_guardPaths() {
        when(weeklyRepository.findByWeekStart(WEEK))
                .thenReturn(
                        Optional.of(
                                IndustryWeeklyReport.success(
                                        WEEK, "{}", "[]", null, "v1.0", "b", NOW)));
        assertThatThrownBy(() -> service.retry(WEEK))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(
                                                ErrorCode.INDUSTRY_WEEKLY_REPORT_ALREADY_SUCCESS));

        when(weeklyRepository.findByWeekStart("2026-09-14")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry("2026-09-14"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_WEEKLY_REPORT_NOT_FOUND));
        verify(jobCenter, org.mockito.Mockito.never()).trigger(any());
    }

    private static IndustryWeeklyReport report(String weekStart) {
        return IndustryWeeklyReport.reconstruct(
                Math.abs(weekStart.hashCode() % 10000L),
                weekStart,
                ReportStatus.SUCCESS,
                "{\"summary\":\"s\",\"totalNews\":1,\"totalEvents\":1}",
                "[]",
                null,
                "v1.0",
                "b",
                NOW,
                NOW);
    }
}
