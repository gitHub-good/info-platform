package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.application.jobrun.JobCenterFacade.TriggerResult;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IndustryReportService 单测（T124，方案 §4.8）：列表游标分页（lookahead 精确 hasMore）、详情 30078/非法日期 30076、重试矩阵
 * （FAILED→202 受理 arm+trigger；SUCCESS→30077；不存在→30078；trigger 拒绝时 disarm 回滚）。AAA 结构。
 */
class IndustryReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    private DailyReportRepository reportRepository;
    private IndustryDailyReportJob reportJob;
    private JobCenterFacade jobCenter;
    private IndustryReportService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(DailyReportRepository.class);
        reportJob = mock(IndustryDailyReportJob.class);
        jobCenter = mock(JobCenterFacade.class);
        service =
                new IndustryReportService(
                        reportRepository, reportJob, jobCenter, new ObjectMapper());
    }

    private static IndustryDailyReport successReport(String date, long id) {
        return IndustryDailyReport.reconstruct(
                id,
                date,
                ReportStatus.SUCCESS,
                "{\"summary\":\"s\",\"totalNews\":10,\"totalEvents\":2,\"narrativeDegraded\":false}",
                "[{\"industry\":\"银行\"}]",
                null,
                "v1.0",
                "b",
                NOW,
                NOW);
    }

    @Test
    void list_firstPageDefaultsLimit10_withPreciseNextCursor() {
        // Arrange：11 行存量（lookahead 多读一行判定 hasMore）
        when(reportRepository.findPage(null, 11))
                .thenReturn(
                        java.util.stream.LongStream.rangeClosed(1, 11)
                                .mapToObj(i -> successReport("2026-09-%02d".formatted(23 - i), i))
                                .toList());

        // Act
        IndustryReportListView view = service.list(null, null);

        // Assert：首页 10 行 + nextBeforeId = 本页末行 id；摘要/计数透出
        assertThat(view.reports()).hasSize(10);
        assertThat(view.nextBeforeId()).isEqualTo(10L);
        assertThat(view.reports().get(0).reportDate()).isEqualTo("2026-09-22");
        assertThat(view.reports().get(0).summary()).isEqualTo("s");
        assertThat(view.reports().get(0).totalNews()).isEqualTo(10);
        assertThat(view.reports().get(0).totalEvents()).isEqualTo(2);
        assertThat(view.reports().get(0).narrativeDegraded()).isFalse();
    }

    @Test
    void list_lastPage_noNextCursor() {
        when(reportRepository.findPage(10L, 11))
                .thenReturn(List.of(successReport("2026-09-13", 11L)));

        IndustryReportListView view = service.list(10L, null);

        assertThat(view.reports()).hasSize(1);
        assertThat(view.nextBeforeId()).isNull();
    }

    @Test
    void list_limitOutOfRange_rejected30076() {
        assertThatThrownBy(() -> service.list(null, 0))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID));
        assertThatThrownBy(() -> service.list(null, 51))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID));
        verifyNoInteractions(reportRepository);
    }

    @Test
    void detail_returnsContentAndHeatTopJson() {
        when(reportRepository.findByReportDate("2026-09-22"))
                .thenReturn(Optional.of(successReport("2026-09-22", 1L)));

        IndustryReportDetailView view = service.detail("2026-09-22");

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.status()).isEqualTo("SUCCESS");
        assertThat(view.content().get("summary").asText()).isEqualTo("s");
        assertThat(view.heatTop().get(0).get("industry").asText()).isEqualTo("银行");
        assertThat(view.basis()).isEqualTo("b");
    }

    @Test
    void detail_notFound_30078() {
        when(reportRepository.findByReportDate("2026-09-22")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail("2026-09-22"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_REPORT_NOT_FOUND));
    }

    @Test
    void detail_invalidDateFormat_30076() {
        assertThatThrownBy(() -> service.detail("2026/09/22"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.PIPELINE_CONFIG_INVALID));
        verifyNoInteractions(reportRepository);
    }

    @Test
    void retry_failedRow_armsDateAndTriggers202() {
        // Arrange：FAILED 行 → armRetry + trigger（executionId 受理回执）
        when(reportRepository.findByReportDate("2026-09-22"))
                .thenReturn(
                        Optional.of(
                                IndustryDailyReport.reconstruct(
                                        1L,
                                        "2026-09-22",
                                        ReportStatus.FAILED,
                                        null,
                                        null,
                                        "llm 超时",
                                        null,
                                        null,
                                        NOW,
                                        NOW)));
        when(jobCenter.trigger("INDUSTRY_DAILY_REPORT"))
                .thenReturn(new TriggerResult(99L, "STARTED"));

        // Act
        IndustryReportService.RetryAcceptance acceptance = service.retry("2026-09-22");

        // Assert
        assertThat(acceptance.executionId()).isEqualTo(99L);
        verify(reportJob).armRetry(java.time.LocalDate.parse("2026-09-22"));
        verify(jobCenter).trigger("INDUSTRY_DAILY_REPORT");
        verify(reportJob, never()).disarmRetry();
    }

    @Test
    void retry_alreadySuccess_30077_conflict() {
        when(reportRepository.findByReportDate("2026-09-22"))
                .thenReturn(Optional.of(successReport("2026-09-22", 1L)));

        assertThatThrownBy(() -> service.retry("2026-09-22"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_REPORT_ALREADY_SUCCESS));
        verifyNoInteractions(jobCenter);
        verify(reportJob, never()).armRetry(any());
    }

    @Test
    void retry_notFound_30078() {
        when(reportRepository.findByReportDate("2026-09-22")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry("2026-09-22"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.INDUSTRY_REPORT_NOT_FOUND));
        verifyNoInteractions(jobCenter);
    }

    @Test
    void retry_triggerRejected_disarmsToAvoidPollutingNextScheduledRun() {
        // Arrange：FAILED 行但 Job 运行中（409/30063）→ disarm 回滚 armed
        when(reportRepository.findByReportDate("2026-09-22"))
                .thenReturn(
                        Optional.of(
                                IndustryDailyReport.reconstruct(
                                        1L,
                                        "2026-09-22",
                                        ReportStatus.FAILED,
                                        null,
                                        null,
                                        "llm 超时",
                                        null,
                                        null,
                                        NOW,
                                        NOW)));
        when(jobCenter.trigger("INDUSTRY_DAILY_REPORT"))
                .thenThrow(new BusinessException(ErrorCode.JOB_ALREADY_RUNNING, "运行中"));

        // Act + Assert
        assertThatThrownBy(() -> service.retry("2026-09-22"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.JOB_ALREADY_RUNNING));
        verify(reportJob).armRetry(java.time.LocalDate.parse("2026-09-22"));
        verify(reportJob).disarmRetry();
    }
}
