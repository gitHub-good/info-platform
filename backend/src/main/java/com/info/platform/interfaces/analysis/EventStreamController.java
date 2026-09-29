package com.info.platform.interfaces.analysis;

import com.info.platform.application.analysis.EventStreamQueryService;
import com.info.platform.application.analysis.ImpactChainService;
import com.info.platform.application.analysis.ImpactChainView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.PageQuery;
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
 *   <li>{@code GET /api/v1/events?type=&industry=&importance=&direction=&market=&beforeId=&limit=} ——
 *       全量结构化事件卡片流（id DESC 游标分页；筛选可空，枚举非法/limit 越界 400/30079 字段级；卡片字段面 = {@code {id, eventType,
 *       summary, industries, direction, importance, figures, subjects, quote, newsId, newsTitle,
 *       newsUrl, eventTime}}）。M29 T254 补交付（P1-01 修复，方案 §5.4）：{@code market} 可选参数（两模式均可用）——过滤语义 =
 *       事件关联标的（subjects code 前缀）含该市场标的；缺省与显式 A_SHARE 等价全量（A 股零回归），HK/US 过滤，非法值（含
 *       INDEX/SECTOR）400/30079；industry 校验随 market 口径切换（A 股申万 31 / 港 31 直采 / 美 40 归并——跨市场重名由 market 消歧）。
 *   <li>{@code GET /api/v1/events?type=&...&market=&page=&size=} —— <b>页码模式</b>（M25 T220，V3.0
 *       REQ-20260928-21 拍板四，M9 PageQuery 双模式同端点分派）：{@code page} 参数出现即页码模式（{items, total, page,
 *       size, industryFilterGroups}，offset 语义，越界页 200 + 空列表）；缺席走游标路径（字节级不动）。页码模式参数校验经 {@link
 *       PageQuery} 共用件（{@code 2001 PARAM_INVALID} 段）： {@code page≥1}（上限 1,000,000 防 offset 溢出）、{@code
 *       size} 缺省 20、1~50 越界 400 拒绝不截断、{@code page} 与 {@code beforeId} 互斥 400、{@code limit}
 *       仅游标模式可用（页码模式请用 size）。五维筛选两模式均可用；页码模式响应恒附 {@code industryFilterGroups}
 *       三市场行业分组（M29 §5.4 契约增量，前端过滤器下拉消费）。
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

    /**
     * 事件流双模式分页：page 出现即页码模式（M9 PageQuery），缺席走 beforeId 游标 + limit 缺省 20 ≤50； market 两模式均可用（M29
     * §5.4：缺省/A_SHARE 全量、HK/US 标的市场过滤）。
     *
     * @return 游标模式 200 + {total, items[], nextBeforeId}；页码模式 200 + {total, items[], page, size,
     *     industryFilterGroups}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "importance", required = false) String importance,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "market", required = false) String market,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        PageQuery pageQuery = PageQuery.resolve(page, size, beforeId);
        if (pageQuery != null) {
            rejectLimitInPageMode(limit);
            return Result.ok(
                    queryService.listPaged(
                            type,
                            industry,
                            importance,
                            direction,
                            market,
                            pageQuery.page(),
                            pageQuery.size()));
        }
        return Result.ok(
                queryService.list(type, industry, importance, direction, market, beforeId, limit));
    }

    /** limit 为游标模式专属（页码模式请用 size——M9/资讯库同例）。 */
    private static void rejectLimitInPageMode(Integer limit) {
        if (limit != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "limit 仅游标模式可用（页码模式请使用 size）");
        }
    }

    /** 事件影响链（M17 T144 事件详情扩展区块数据面）。 */
    @GetMapping("/{eventId}/impact-chains")
    public Result<ImpactChainView> impactChains(@PathVariable long eventId) {
        return Result.ok(impactChainService.chainsForEvent(eventId));
    }
}
