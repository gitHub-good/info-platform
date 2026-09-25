package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.IndustryHeatBoardView;
import com.info.platform.application.analysis.IndustryHeatItemsView;
import com.info.platform.application.analysis.IndustryHeatQueryService;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业热度接口（M15 T123，方案 §4.8，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/industry-heat?window=H24|D7} —— 热度榜（31 行业降序 + basis 脚注 + 护栏徽章；window 缺省
 *       H24，非法 400/30076）
 *   <li>{@code GET
 *       /api/v1/industry-heat/{industry}/items?window=&type=news|events&beforeId=&limit=} ——
 *       行业下钻（news 含 L2 事件 标记 / events 与事件流同口径；行业名非申万枚举 400/30076；limit 缺省 20 ≤50 越界拒绝）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/industry-heat")
public class IndustryHeatController {

    private final IndustryHeatQueryService queryService;

    public IndustryHeatController(IndustryHeatQueryService queryService) {
        this.queryService = queryService;
    }

    /** 热度榜。 */
    @GetMapping
    public Result<IndustryHeatBoardView> board(
            @RequestParam(value = "window", required = false) String window) {
        return Result.ok(queryService.board(window));
    }

    /** 行业下钻（news / events 双清单，beforeId 游标分页）。 */
    @GetMapping("/{industry}/items")
    public Result<IndustryHeatItemsView> items(
            @PathVariable String industry,
            @RequestParam(value = "window", required = false) String window,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(queryService.items(industry, window, type, beforeId, limit));
    }
}
