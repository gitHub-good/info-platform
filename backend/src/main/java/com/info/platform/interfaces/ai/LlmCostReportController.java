package com.info.platform.interfaces.ai;

import com.info.platform.application.ai.LlmCostReport;
import com.info.platform.application.ai.LlmCostReportService;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 成本报表接口（T30）。
 *
 * <p>受 JWT 保护（{@code /api/v1/llm-cost-report} 不在 {@code JwtAuthFilter} 白名单）。 管理视角全局数据（不按用户隔离），对齐
 * {@code JobLogController} 口径；不留敏感信息——api-key 等密钥不出现在留痕与报表。
 *
 * <h2>端点</h2>
 *
 * <ul>
 *   <li>{@code GET /api/v1/llm-cost-report?window=today|7d|30d} —— 窗口汇总（总成本/调用次数/成功率/缓存命中率/ token
 *       用量）+ provider/场景成本分布 + 今日用户预算余量告警状态。window 缺省 7d；非法值 400（2001）。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/llm-cost-report")
public class LlmCostReportController {

    private final LlmCostReportService costReportService;

    public LlmCostReportController(LlmCostReportService costReportService) {
        this.costReportService = costReportService;
    }

    /**
     * 成本报表。
     *
     * @param window 时间窗键 today/7d/30d；缺省 7d
     */
    @GetMapping
    public Result<LlmCostReport> report(@RequestParam(required = false) String window) {
        return Result.ok(costReportService.report(window));
    }
}
