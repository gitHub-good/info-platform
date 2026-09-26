package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.IndustryWeeklyReportDetailView;
import com.info.platform.application.analysis.IndustryWeeklyReportListView;
import com.info.platform.application.analysis.IndustryWeeklyReportService;
import com.info.platform.interfaces.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业周报接口（M17 T145，方案 §4.8，Bearer JWT；字面量 {@code weekly} 段与日报 {@code /{reportDate}} 路由无歧义——精确匹配优先）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/industry-reports/weekly?beforeId=&limit=} —— 周报列表（week_start DESC
 *       游标分页，limit 缺省 10 ≤50）
 *   <li>{@code GET /api/v1/industry-reports/weekly/{weekStart}} —— 单周详情（content/heatTop 五区块 JSON
 *       全量；不存在 404/30084）
 *   <li>{@code POST /api/v1/industry-reports/weekly/{weekStart}/retry} —— 重试 FAILED 周报（202 受理走
 *       JobExecutor 手动通道； 已 SUCCESS 409/30085）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/industry-reports/weekly")
public class IndustryWeeklyReportController {

    private final IndustryWeeklyReportService weeklyService;

    public IndustryWeeklyReportController(IndustryWeeklyReportService weeklyService) {
        this.weeklyService = weeklyService;
    }

    /** 周报列表。 */
    @GetMapping
    public Result<IndustryWeeklyReportListView> list(
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(weeklyService.list(beforeId, limit));
    }

    /** 单周详情。 */
    @GetMapping("/{weekStart}")
    public Result<IndustryWeeklyReportDetailView> detail(@PathVariable String weekStart) {
        return Result.ok(weeklyService.detail(weekStart));
    }

    /** 重试 FAILED 周报（异步受理 202）。 */
    @PostMapping("/{weekStart}/retry")
    public ResponseEntity<Result<IndustryWeeklyReportService.RetryAcceptance>> retry(
            @PathVariable String weekStart) {
        return ResponseEntity.accepted().body(Result.ok(weeklyService.retry(weekStart)));
    }
}
