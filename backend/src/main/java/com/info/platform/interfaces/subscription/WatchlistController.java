package com.info.platform.interfaces.subscription;

import com.info.platform.application.subscription.WatchlistItemView;
import com.info.platform.application.subscription.WatchlistItemsPagedView;
import com.info.platform.application.subscription.WatchlistService;
import com.info.platform.application.subscription.WatchlistView;
import com.info.platform.interfaces.common.PageQuery;
import com.info.platform.interfaces.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自选清单管理接口（对齐技术方案 §4.1.2）。
 *
 * <p>所有端点受 JWT 保护（T17 JwtAuthFilter 写入 {@code UserContext}），行级权限在应用层 {@link WatchlistService} 取
 * {@code UserContext.get().userId()} 落实——用户只能操作自己的清单。
 *
 * <p>幂等（§4.4「幂等业务语义键」，Idempotency-Key 头为业务语义键）：watchlist 表无 idempotency_key 列， 去重走自然键——创建清单
 * 键=userId+name、加标的键=userId+watchlistId+subjectId，重复请求→30011/409 不产生重复行（DB {@code
 * UNIQUE(watchlist_id, subject_id)} 为加标的最后防线）。 Idempotency-Key 头接受并记审计日志，实际去重由自然键承担。
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /** 列出当前用户全部清单（含清单项）。 */
    @GetMapping
    public Result<List<WatchlistView>> list() {
        return Result.ok(watchlistService.listMyWatchlists());
    }

    /** 创建清单；同名→30011/409（幂等）。 */
    @PostMapping
    public Result<WatchlistView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWatchlistRequest request) {
        log.debug("创建清单 Idempotency-Key={}", idempotencyKey);
        return Result.ok(watchlistService.createWatchlist(request.name(), request.remark()));
    }

    /** 改名；不存在→30010/404，越权→30012/403，与他人启用清单同名→30011/409；改名 = 当前名幂等成功。 */
    @PatchMapping("/{id}")
    public Result<WatchlistView> rename(
            @PathVariable Long id, @Valid @RequestBody RenameWatchlistRequest request) {
        return Result.ok(watchlistService.renameWatchlist(id, request.name()));
    }

    /** 删除清单（软删除）；不存在→30010/404，越权→30012/403。删除后异动检测同步停止。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        watchlistService.deleteWatchlist(id);
        return Result.ok();
    }

    /** 单清单（含清单项）；不存在→30010/404，越权→30012/403。 */
    @GetMapping("/{id}")
    public Result<WatchlistView> get(@PathVariable Long id) {
        return Result.ok(watchlistService.getWatchlist(id));
    }

    /**
     * 清单项分页+排序（M9 页码契约 {total, items, page, size} + sort/dir 回显）。
     *
     * <p>sort ∈ {addedAt, price, changePct}（缺省 addedAt）、dir ∈ {asc, desc}（缺省 asc）； 行情两列内联
     * （实时源缓存），行情缺失行 price/changePct=null 恒沉底。page/size 边界同全站（1~50，越界 400 拒绝不截断）。
     */
    @GetMapping("/{id}/items")
    public Result<WatchlistItemsPagedView> listItems(
            @PathVariable Long id,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size,
            @RequestParam(value = "sort", defaultValue = "addedAt") String sort,
            @RequestParam(value = "dir", defaultValue = "asc") String dir) {
        PageQuery query = PageQuery.resolve(page, size, null);
        return Result.ok(
                watchlistService.listItemsPaged(id, query.page(), query.size(), sort, dir));
    }

    /** 加标的到清单；清单不存在→30010/404、越权→30012/403、标的不存在→30001/404、已在清单→30011/409（幂等）。 */
    @PostMapping("/{id}/items")
    public Result<WatchlistItemView> addItem(
            @PathVariable Long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddItemRequest request) {
        log.debug("加标的 Idempotency-Key={}, watchlistId={}", idempotencyKey, id);
        return Result.ok(
                watchlistService.addItem(id, request.subjectId(), request.anomalyThreshold()));
    }

    /** 移除清单项（行级校验；幂等：已不存在不报错）。 */
    @DeleteMapping("/{id}/items/{itemId}")
    public Result<Void> removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        watchlistService.removeItem(id, itemId);
        return Result.ok();
    }

    /** 改异动阈值；清单不存在→30010/404、越权→30012/403、清单项不存在→30010/404。 */
    @PatchMapping("/{id}/items/{itemId}")
    public Result<WatchlistItemView> updateThreshold(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateThresholdRequest request) {
        return Result.ok(
                watchlistService.updateItemThreshold(id, itemId, request.anomalyThreshold()));
    }

    /** 创建清单请求体。 */
    public record CreateWatchlistRequest(
            @NotBlank(message = "清单名不能为空") String name, String remark) {}

    /** 改名请求体。 */
    public record RenameWatchlistRequest(@NotBlank(message = "清单名不能为空") String name) {}

    /** 加标的请求体（anomalyThreshold 可空，缺省走默认 3.00）。 */
    public record AddItemRequest(
            @NotNull(message = "subjectId 不能为空") Long subjectId,
            @PositiveOrZero(message = "anomalyThreshold 不能为负") BigDecimal anomalyThreshold) {}

    /** 改异动阈值请求体。 */
    public record UpdateThresholdRequest(
            @NotNull(message = "anomalyThreshold 不能为空") @PositiveOrZero(message = "anomalyThreshold 不能为负")
                    BigDecimal anomalyThreshold) {}
}
