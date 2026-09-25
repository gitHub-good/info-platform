package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.EventStreamQueryService;
import com.info.platform.application.analysis.EventStreamView;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
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
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    private final EventStreamQueryService queryService;

    public EventStreamController(EventStreamQueryService queryService) {
        this.queryService = queryService;
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
}
