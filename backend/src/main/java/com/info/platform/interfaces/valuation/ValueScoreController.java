package com.info.platform.interfaces.valuation;

import com.info.platform.application.valuation.ScoreWeightConfigFacade;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsUpdate;
import com.info.platform.application.valuation.ScoreWeightConfigFacade.WeightsView;
import com.info.platform.application.valuation.ValueScoreQueryService;
import com.info.platform.application.valuation.ValueScoreQueryService.CoverageView;
import com.info.platform.application.valuation.ValueScoreQueryService.ScoreView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 价值评分接口（M20 T170/T171/T172，方案 §4.7，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/value-scores/coverage?date=} —— 覆盖对账（M20 验收口径常驻；date 缺省最新快照日， 非法
 *       400/30088）
 *   <li>{@code GET /api/v1/subjects/{subjectId}/value-score} —— 标的价值评分（最新快照 + 五维分解 + 百分位， 无快照
 *       404/30086；依据事件下钻走既有事件端点——本端点只回 eventId）
 *   <li>{@code GET/PATCH /api/v1/value-scores/weights} —— 权重与阈值（任务中心 FACTOR_SNAPSHOT 编辑 Dialog 数据源；
 *       PATCH 全量替换，非法字段级 30087 原值保留，可选 expectedUpdatedAt 不符 30065，保存即热生效——下一轮快照按新参数 与 basis
 *       计算，T172）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ValueScoreController {

    private final ValueScoreQueryService queryService;
    private final ScoreWeightConfigFacade weightFacade;

    public ValueScoreController(
            ValueScoreQueryService queryService, ScoreWeightConfigFacade weightFacade) {
        this.queryService = queryService;
        this.weightFacade = weightFacade;
    }

    /** 覆盖对账（快照行数 vs 活跃标的数 + 例外口径 flagCounts）。 */
    @GetMapping("/value-scores/coverage")
    public Result<CoverageView> coverage(
            @RequestParam(value = "date", required = false) String date) {
        return Result.ok(queryService.coverage(date));
    }

    /** 标的价值评分（最新快照 + 五维分解 + 查询层百分位 + 依据明细）。 */
    @GetMapping("/subjects/{subjectId}/value-score")
    public Result<ScoreView> valueScore(@PathVariable long subjectId) {
        return Result.ok(queryService.valueScore(subjectId));
    }

    /** 当前权重与阈值视图（13 参数 + basis 派生指纹 + updatedAt；键缺失 = 代码缺省 + null）。 */
    @GetMapping("/value-scores/weights")
    public Result<WeightsView> weights() {
        return Result.ok(weightFacade.view());
    }

    /** 全量替换权重与阈值（非法 30087 字段级、原值保留；expectedUpdatedAt 不符 30065/409）。 */
    @PatchMapping("/value-scores/weights")
    public Result<WeightsView> updateWeights(@RequestBody WeightsUpdate update) {
        return Result.ok(weightFacade.update(update));
    }
}
