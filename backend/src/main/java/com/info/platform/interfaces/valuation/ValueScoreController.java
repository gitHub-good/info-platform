package com.info.platform.interfaces.valuation;

import com.info.platform.application.valuation.ValueScoreQueryService;
import com.info.platform.application.valuation.ValueScoreQueryService.CoverageView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 价值评分接口（M20 T170，方案 §4.7，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/value-scores/coverage?date=} —— 覆盖对账（M20 验收口径常驻；date 缺省最新快照日， 非法
 *       400/30088）
 *   <li>{@code GET /api/v1/subjects/{subjectId}/value-score} —— 标的价值评分（最新快照 + 五维分解 + 百分位， 无快照
 *       404/30086；T171 落地）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ValueScoreController {

    private final ValueScoreQueryService queryService;

    public ValueScoreController(ValueScoreQueryService queryService) {
        this.queryService = queryService;
    }

    /** 覆盖对账（快照行数 vs 活跃标的数 + 例外口径 flagCounts）。 */
    @GetMapping("/value-scores/coverage")
    public Result<CoverageView> coverage(
            @RequestParam(value = "date", required = false) String date) {
        return Result.ok(queryService.coverage(date));
    }
}
