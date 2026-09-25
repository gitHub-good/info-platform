package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.jobrun.JobCenterFacade;
import com.info.platform.domain.analysis.DailyReportRepository;
import com.info.platform.domain.analysis.IndustryDailyReport;
import com.info.platform.domain.analysis.ReportStatus;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 行业日报读与重试服务（应用层，M15 T124，方案 §4.8）：列表（beforeId 游标 + limit 缺省 10）/ 详情（30078）/ 重试（FAILED 校验
 * 30077→409、不存在 30078；202 受理走 {@link JobCenterFacade#trigger} 手动通道——CAS 守卫 + job_execution_log 留痕，
 * 端口在应用层与既有 facade 先例同构）。
 *
 * <p>重试受理链路：校验 FAILED → {@code armRetry(date)} 武装指定日 → {@code trigger}（未受理异常时 disarm 回滚，armed
 * 不污染下一轮定时语义）。
 */
@Service
public class IndustryReportService {

    private static final Logger log = LoggerFactory.getLogger(IndustryReportService.class);

    /** 日报 Job 键（trigger 手动通道锚点）。 */
    static final String REPORT_JOB_KEY = "INDUSTRY_DAILY_REPORT";

    /** 列表页大小缺省（方案 §4.8：缺省 10）。 */
    static final int DEFAULT_LIST_LIMIT = 10;

    /** 列表页大小上限（对齐 M9 游标分页 50 上限口径）。 */
    static final int LIST_LIMIT_MAX = 50;

    /** 列表预取多读一行（hasMore 精确判定，无 total 近似）。 */
    private static final int LOOKAHEAD = 1;

    private final DailyReportRepository reportRepository;
    private final IndustryDailyReportJob reportJob;
    private final JobCenterFacade jobCenter;
    private final ObjectMapper objectMapper;

    public IndustryReportService(
            DailyReportRepository reportRepository,
            IndustryDailyReportJob reportJob,
            JobCenterFacade jobCenter,
            ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.reportJob = reportJob;
        this.jobCenter = jobCenter;
        this.objectMapper = objectMapper;
    }

    /** 列表（report_date DESC，beforeId 游标；limit 缺省 10 ≤50 越界拒绝不截断）。 */
    public IndustryReportListView list(Long beforeId, Integer limit) {
        int pageSize = resolveLimit(limit);
        // 多读一行精确判定 hasMore（无 total 近似——页脚 nextBeforeId 语义严格）
        List<IndustryDailyReport> page = reportRepository.findPage(beforeId, pageSize + LOOKAHEAD);
        boolean hasMore = page.size() > pageSize;
        List<IndustryDailyReport> rows = hasMore ? page.subList(0, pageSize) : page;
        Long nextBeforeId = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
        return new IndustryReportListView(rows.stream().map(this::toItem).toList(), nextBeforeId);
    }

    /** 详情（不存在 30078；日期格式非法 30076）。 */
    public IndustryReportDetailView detail(String reportDate) {
        parseDate(reportDate);
        IndustryDailyReport report =
                reportRepository
                        .findByReportDate(reportDate)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_REPORT_NOT_FOUND,
                                                "该日日报不存在: " + reportDate));
        return new IndustryReportDetailView(
                report.getId(),
                report.getReportDate(),
                report.getStatus().name(),
                parseJson(report.getContent()),
                parseJson(report.getHeatTop()),
                report.getErrorMessage(),
                report.getPromptVersion(),
                report.getBasis(),
                isoOf(report.getCreatedAt()),
                isoOf(report.getUpdatedAt()));
    }

    /** 重试 FAILED 日报（202 受理；已 SUCCESS 30077/409；不存在 30078；日期格式非法 30076）。 */
    public RetryAcceptance retry(String reportDate) {
        parseDate(reportDate);
        IndustryDailyReport report =
                reportRepository
                        .findByReportDate(reportDate)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INDUSTRY_REPORT_NOT_FOUND,
                                                "该日日报不存在: " + reportDate));
        if (report.getStatus() == ReportStatus.SUCCESS) {
            throw new BusinessException(
                    ErrorCode.INDUSTRY_REPORT_ALREADY_SUCCESS,
                    "该日日报已成功生成（" + reportDate + "），无需重试");
        }
        reportJob.armRetry(LocalDate.parse(reportDate));
        try {
            JobCenterFacade.TriggerResult result = jobCenter.trigger(REPORT_JOB_KEY);
            log.info("行业日报重试已受理: date={} executionId={}", reportDate, result.executionId());
            return new RetryAcceptance(result.executionId());
        } catch (RuntimeException e) {
            // 未受理（如 409 运行中）：解除武装，避免污染下一轮定时语义
            reportJob.disarmRetry();
            throw e;
        }
    }

    private IndustryReportListView.ItemView toItem(IndustryDailyReport report) {
        JsonNode content = parseJson(report.getContent());
        return new IndustryReportListView.ItemView(
                report.getId() == null ? 0L : report.getId(),
                report.getReportDate(),
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
            log.warn("日报 JSON 列解析失败（透出 null）: {}", e.getMessage());
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

    /** 重试受理结果（executionId = job_execution_log 落痕 id，状态恒 STARTED）。 */
    public record RetryAcceptance(long executionId) {}

    private LocalDate parseDate(String reportDate) {
        try {
            return LocalDate.parse(reportDate);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(
                    ErrorCode.PIPELINE_CONFIG_INVALID,
                    "reportDate: 须为 yyyy-MM-dd，当前值 " + reportDate);
        }
    }
}
