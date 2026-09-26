package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.EventStreamQueryService;
import com.info.platform.application.analysis.EventStreamView;
import com.info.platform.application.analysis.ImpactChainService;
import com.info.platform.application.analysis.ImpactChainView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件流接口（M15 T127，方案 §4.8，Bearer JWT）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/events?type=&industry=&importance=&direction=&beforeId=&limit=} ——
 *       全量结构化事件卡片流（id DESC 游标分页；筛选可空，枚举非法/limit 越界 400/30079 字段级；卡片字段面 = {@code {id, eventType,
 *       summary, industries, direction, importance, figures, subjects, quote, newsId, newsTitle,
 *       newsUrl, eventTime}}）
 *   <li>{@code GET /api/v1/events/{eventId}/impact-chains} —— 事件详情影响链区块（M17 T144：HIGH
 *       缓存直返/缺位自愈、MEDIUM 首次展开按需生成并缓存、LOW 空态；事件不存在 404/30083）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    private final EventStreamQueryService queryService;
    private final ImpactChainService impactChainService;

    public EventStreamController(
            EventStreamQueryService queryService, ImpactChainService impactChainService) {
        this.queryService = queryService;
        this.impactChainService = impactChainService;
    }

    /** 事件流分页（四维可空筛选 + beforeId 游标 + limit 缺省 20 ≤50）。 */
    @GetMapping
    public Result<EventStreamView> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "importance", required = false) String importance,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(queryService.list(type, industry, importance, direction, beforeId, limit));
    }

    /** 事件影响链（M17 T144 事件详情扩展区块数据面）。 */
    @GetMapping("/{eventId}/impact-chains")
    public Result<ImpactChainView> impactChains(@PathVariable long eventId) {
        return Result.ok(impactChainService.chainsForEvent(eventId));
    }
}
