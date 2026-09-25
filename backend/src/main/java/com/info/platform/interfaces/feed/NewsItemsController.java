package com.info.platform.interfaces.feed;

import com.info.platform.application.feed.NewsItemsQueryService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一资讯流接口（M13 T104，方案 §4.5）：{@code GET /api/v1/news-items}（Bearer JWT）。
 *
 * <p><b>同端点双模式</b>（M9 / ADR-0035 惯例）：{@code page} 参数出现即页码模式（{items, total, page, size}）； 缺席走游标模式（{items,
 * nextBeforeId}，{@code beforeId} 续取 + {@code limit} 默认 20、1~50 越界 400 拒绝不截断）。 两模式均支持 {@code sourceId}
 * 源过滤；默认排除软删源条目（join info_source）。参数校验经 {@link PageQuery} 共用件（page/cursor 互斥、size 缺省与上限），
 * limit 为游标模式专属（与 page 同现 400——契约确定性优先）。
 */
@RestController
@RequestMapping("/api/v1/news-items")
public class NewsItemsController {

    /** 游标页大小缺省（对齐方案 §4.5：默认 20）。 */
    static final int DEFAULT_LIMIT = PageQuery.DEFAULT_SIZE;

    private final NewsItemsQueryService queryService;

    public NewsItemsController(NewsItemsQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 统一资讯流（双模式分派：page 出现即页码模式，缺席走游标路径）。
     *
     * @return 游标模式 200 + {items[], nextBeforeId}；页码模式 200 + {items[], total, page, size}
     */
    @GetMapping
    public Result<?> list(
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        PageQuery pageQuery = PageQuery.resolve(page, size, beforeId);
        if (pageQuery != null) {
            rejectLimitInPageMode(limit);
            return Result.ok(
                    queryService.listPaged(sourceId, pageQuery.page(), pageQuery.size()));
        }
        return Result.ok(
                queryService.listCursor(sourceId, beforeId, resolvedLimit(limit)));
    }

    /** limit 归一化：缺席取缺省 20；1~50 越界 400 拒绝不截断（对齐 size 口径）。 */
    private static int resolvedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "limit 至少为 1");
        }
        if (limit > PageQuery.MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "limit 超过上限 " + PageQuery.MAX_SIZE);
        }
        return limit;
    }

    private static void rejectLimitInPageMode(Integer limit) {
        if (limit != null) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID, "limit 仅游标模式可用（页码模式请使用 size）");
        }
    }
}
