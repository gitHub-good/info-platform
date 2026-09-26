package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.domain.analysis.IndustryWeeklyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.analysis.WeeklyReportRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 行业周报读与重试服务（应用层，M17 T145，沿 IndustryReportService 同构）：列表（beforeId 游标 + limit 缺省 10）/ 详情 （30084）/
 * 重试（FAILED 校验 30085→409、不存在 30084；202 受理走 {@link JobCenterFacade#trigger} 手动通道）。
 */
@Service
public class IndustryWeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(IndustryWeeklyReportService.class);

    /** 周报 Job 键（trigger 手动通道锚点）。 */
    static final String WEEKLY_JOB_KEY = "INDUSTRY_WEEKLY_REPORT";

    /** 列表页大小缺省。 */
    static final int DEFAULT_LIST_LIMIT = 10;

    /** 列表页大小上限（对齐 M9 游标分页 50 上限口径）。 */
    static final int LIST_LIMIT_MAX = 50;

    /** 列表预取多读一行（hasMore 精确判定）。 */
    private static final int LOOKAHEAD = 1;

    private final WeeklyReportRepository weeklyRepository;
    private final IndustryWeeklyReportJob weeklyJob;
    private final JobCenterFacade jobCenter;
    private final ObjectMapper objectMapper;

    public IndustryWeeklyReportService(
            WeeklyReportRepository weeklyRepository,
            IndustryWeeklyReportJob weeklyJob,
            JobCenterFacade jobCenter,
            ObjectMapper objectMapper) {
        this.weeklyRepository = weeklyRepository;
        this.weeklyJob = weeklyJob;
        this.jobCenter = jobCenter;
        this.objectMapper = objectMapper;
    }

    /** 列表（week_start DESC，beforeId 游标；limit 缺省 10 ≤50 越界拒绝不截断）。 */
    public IndustryWeeklyReportListView list(Long beforeId, Integer limit) {
        int pageSize = resolveLimit(limit);
        List<IndustryWeeklyReport> page = weeklyRepository.findPage(beforeId, pageSize + LOOKAHEAD);
        boolean hasMore = page.size() > pageSize;
        List<IndustryWeeklyReport> rows = hasMore ? page.subList(0, pageSize) : page;
        Long nextBeforeId = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
        return new IndustryWeeklyReportListView(
                rows.stream().map(this::toItem).toList(), nextBeforeId);
    }

    /** 详情（不存在 30084；周锚格式非法 30076）。 */
    public IndustryWeeklyReportDetailView detail(String weekStart) {
        parseWeek(weekStart);
        IndustryWeeklyReport report =
                weeklyRepository
                        .findByWeekStart(weekStart)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_WEEKLY_REPORT_NOT_FOUND,
                                                "该周周报不存在: " + weekStart));
        return new IndustryWeeklyReportDetailView(
                report.getId() == null ? 0L : report.getId(),
                report.getWeekStart(),
                report.getStatus().name(),
                parseJson(report.getContent()),
                parseJson(report.getHeatTop()),
                report.getErrorMessage(),
                report.getPromptVersion(),
                report.getBasis(),
                isoOf(report.getCreatedAt()),
                isoOf(report.getUpdatedAt()));
    }

    /** 重试 FAILED 周报（202 受理；已 SUCCESS 30085/409；不存在 30084；周锚格式非法 30076）。 */
    public RetryAcceptance retry(String weekStart) {
        parseWeek(weekStart);
        IndustryWeeklyReport report =
                weeklyRepository
                        .findByWeekStart(weekStart)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_WEEKLY_REPORT_NOT_FOUND,
                                                "该周周报不存在: " + weekStart));
        if (report.getStatus() == ReportStatus.SUCCESS) {
            throw new BusinessException(
                    ErrorCode.INDUSTRY_WEEKLY_REPORT_ALREADY_SUCCESS,
                    "该周周报已成功生成（" + weekStart + "），无需重试");
        }
        weeklyJob.armRetry(LocalDate.parse(weekStart));
        try {
            JobCenterFacade.TriggerResult result = jobCenter.trigger(WEEKLY_JOB_KEY);
            log.info("行业周报重试已受理: weekStart={} executionId={}", weekStart, result.executionId());
            return new RetryAcceptance(result.executionId());
        } catch (RuntimeException e) {
            weeklyJob.disarmRetry();
            throw e;
        }
    }

    private IndustryWeeklyReportListView.ItemView toItem(IndustryWeeklyReport report) {
        JsonNode content = parseJson(report.getContent());
        return new IndustryWeeklyReportListView.ItemView(
                report.getId() == null ? 0L : report.getId(),
                report.getWeekStart(),
                report.getStatus().name(),
                textOf(content, "summary"),
                longOf(content, "totalNews"),
                longOf(content, "totalEvents"),
                content != null && content.path("narrativeDegraded").asBoolean(false),
                isoOf(report.getCreatedAt()),
                isoOf(report.getUpdatedAt()));
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIST_LIMIT;
        }
        if (limit < 1 || limit > LIST_LIMIT_MAX) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID,
                    "limit: 须在 1~" + LIST_LIMIT_MAX + "（越界拒绝不截断），当前值 " + limit);
        }
        return limit;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("周报 JSON 列解析失败（透出 null）: {}", e.getMessage());
            return null;
        }
    }

    private static String textOf(JsonNode content, String field) {
        JsonNode node = content == null ? null : content.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static long longOf(JsonNode content, String field) {
        JsonNode node = content == null ? null : content.get(field);
        return node != null && node.canConvertToLong() ? node.asLong() : 0L;
    }

    private static String isoOf(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private LocalDate parseWeek(String weekStart) {
        LocalDate anchor;
        try {
            anchor = LocalDate.parse(weekStart);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID, "weekStart: 须为 yyyy-MM-dd，当前值 " + weekStart);
        }
        if (anchor.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID, "weekStart: 须为周一（周窗锚点），当前值 " + weekStart);
        }
        return anchor;
    }

    /** 重试受理结果（executionId = job_execution_log 落痕 id）。 */
    public record RetryAcceptance(long executionId) {}
}
