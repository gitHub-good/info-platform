package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.IndustryReportDetailView;
import com.info.platform.application.analysis.IndustryReportListView;
import com.info.platform.application.analysis.IndustryReportService;
import com.info.platform.interfaces.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业日报接口（M15 T124，方案 §4.8，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/industry-reports?beforeId=&limit=} —— 日报列表（report_date DESC 游标分页，limit
 *       缺省 10 ≤50）
 *   <li>{@code GET /api/v1/industry-reports/{reportDate}} —— 单日详情（content/heatTop JSON 全量；不存在
 *       404/30078）
 *   <li>{@code POST /api/v1/industry-reports/{reportDate}/retry} —— 重试 FAILED 日报（202 受理走
 *       JobExecutor 手动通道； 已 SUCCESS 409/30077；不存在 404/30078）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/industry-reports")
public class IndustryReportController {

    private final IndustryReportService reportService;

    public IndustryReportController(IndustryReportService reportService) {
        this.reportService = reportService;
    }

    /** 日报列表。 */
    @GetMapping
    public Result<IndustryReportListView> list(
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(reportService.list(beforeId, limit));
    }

    /** 单日详情。 */
    @GetMapping("/{reportDate}")
    public Result<IndustryReportDetailView> detail(@PathVariable String reportDate) {
        return Result.ok(reportService.detail(reportDate));
    }

    /** 重试 FAILED 日报（异步受理 202）。 */
    @PostMapping("/{reportDate}/retry")
    public ResponseEntity<Result<IndustryReportService.RetryAcceptance>> retry(
            @PathVariable String reportDate) {
        return ResponseEntity.accepted().body(Result.ok(reportService.retry(reportDate)));
    }
}
