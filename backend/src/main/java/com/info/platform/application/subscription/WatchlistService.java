package com.info.platform.application.subscription;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectQuote;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自选清单应用服务：CRUD 编排 + 行级权限 + 幂等（对齐技术方案 §4.1.2、§4.4）。
 *
 * <p>行级权限：每个方法首步 {@code long userId = UserContext.get().userId()}（T17 JwtAuthFilter 写入）， 所有
 * Repository 调用带 {@code userId}——端口层 {@code WHERE user_id=?} 即约束，用户只能操作自己的清单。 越权访问清单：清单存在但非本人 →
 * {@link ErrorCode#WATCHLIST_FORBIDDEN}（30012/403）；清单不存在 → {@link
 * ErrorCode#WATCHLIST_NOT_FOUND}（30010/404）。
 *
 * <p>幂等（§4.4「幂等业务语义键」，watchlist 表无 idempotency_key 列，走自然键去重）：
 *
 * <ul>
 *   <li>创建清单：键=userId+name，{@link WatchlistRepository#existsByOwnerIdAndName} 查重，同名→30011/409
 *       （重复请求不产生重复行）
 *   <li>加标的：键=userId+watchlistId+subjectId，{@link
 *       WatchlistRepository#existsItemByWatchlistAndSubject} 查重 + DB {@code UNIQUE(watchlist_id,
 *       subject_id)} 最后防线，已在清单→30011/409
 * </ul>
 *
 * <p>加标的需校验标的存在（跨域注入 {@link SubjectRepository} 端口，任务单 T11 明确）→不存在抛 {@link
 * ErrorCode#SUBJECT_NOT_FOUND}（30001/404）。
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistRepository repository;
    private final SubjectRepository subjectRepository;
    private final AggregationService aggregationService;

    public WatchlistService(
            WatchlistRepository repository,
            SubjectRepository subjectRepository,
            AggregationService aggregationService) {
        this.repository = repository;
        this.subjectRepository = subjectRepository;
        this.aggregationService = aggregationService;
    }

    /** 列出当前用户全部启用清单（含清单项）。 */
    public List<WatchlistView> listMyWatchlists() {
        long userId = currentUserId();
        List<Watchlist> lists = repository.findAllByOwnerId(userId);
        return lists.stream().map(WatchlistView::from).toList();
    }

    /** 单清单（含清单项）；不存在→30010/404，越权→30012/403。 */
    public WatchlistView getWatchlist(Long id) {
        long userId = currentUserId();
        if (!repository.existsById(id)) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
        Watchlist watchlist =
                repository
                        .findByOwnerIdAndId(userId, id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN));
        return WatchlistView.from(watchlist);
    }

    /** 分页排序键白名单：addedAt（加入顺序，缺省）/ price（最新价）/ changePct（涨跌幅）。 */
    private static final Set<String> ITEM_SORT_KEYS = Set.of("addedAt", "price", "changePct");

    /**
     * 清单项分页+排序（M9 页码契约 {total, items, page, size}）。
     *
     * <p>行情不落库（实时源适配器 + 缓存），故 SQL 侧只约束清单归属，排序在应用层内存完成： 全量项 + 批量行情（{@link
     * AggregationService#getQuotes}，享 QUOTE 源缓存）联行 → 排序 → 切页。
     * 清单为个人列表量级（几十项），内存排序成本可忽略；行情缺失行（源失败/类型不适用）按 nulls-last 沉底，不阻断榜单。sort/dir 非白名单 → 2xxx/400
     * 拒绝不静默纠正。
     */
    public WatchlistItemsPagedView listItemsPaged(
            Long watchlistId, int page, int size, String sort, String dir) {
        long userId = currentUserId();
        if (!ITEM_SORT_KEYS.contains(sort)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "sort 仅支持 " + ITEM_SORT_KEYS);
        }
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "dir 仅支持 asc/desc");
        }
        Watchlist watchlist =
                repository
                        .findByOwnerIdAndId(userId, watchlistId)
                        .orElseThrow(
                                () ->
                                        repository.existsById(watchlistId)
                                                ? new BusinessException(
                                                        ErrorCode.WATCHLIST_FORBIDDEN)
                                                : new BusinessException(
                                                        ErrorCode.WATCHLIST_NOT_FOUND));
        List<WatchlistItem> items = watchlist.getItems();
        if (items.isEmpty()) {
            return WatchlistItemsPagedView.empty(page, size, sort, dir);
        }

        Map<Long, SubjectQuote> quotes = new HashMap<>();
        for (SubjectQuote row :
                aggregationService.getQuotes(
                        items.stream().map(WatchlistItem::getSubjectId).toList())) {
            quotes.put(row.id(), row);
        }

        List<WatchlistItemPagedRow> rows = new ArrayList<>(items.size());
        for (WatchlistItem item : items) {
            SubjectQuote quote = quotes.get(item.getSubjectId());
            rows.add(
                    new WatchlistItemPagedRow(
                            item.getId(),
                            item.getSubjectId(),
                            item.getAnomalyThreshold(),
                            quote == null ? null : quote.subjectCode(),
                            quote == null ? null : quote.name(),
                            quote == null ? null : quote.market(),
                            quote == null ? null : quote.industry(),
                            quote == null ? null : decimalOf(quote.quote(), "price"),
                            quote == null ? null : decimalOf(quote.quote(), "changePct")));
        }

        Comparator<WatchlistItemPagedRow> comparator =
                switch (sort) {
                    case "price" -> Comparator.comparing(
                            WatchlistItemPagedRow::price,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    case "changePct" -> Comparator.comparing(
                            WatchlistItemPagedRow::changePct,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    default -> Comparator.comparing(WatchlistItemPagedRow::id);
                };
        if ("desc".equals(dir)) {
            comparator = comparator.reversed();
        }
        // desc 反转会把 nullsLast 变 nullsFirst：null 标志前置恢复「行情缺失恒沉底」；addedAt 键无 null 直通
        String sortKey = sort;
        comparator =
                Comparator.comparing(
                                (WatchlistItemPagedRow row) ->
                                        sortValueOf(row, sortKey) == null ? 1 : 0)
                        .thenComparing(comparator);
        rows.sort(comparator);

        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return new WatchlistItemsPagedView(
                items.size(), List.copyOf(rows.subList(from, to)), page, size, sort, dir);
    }

    /** 排序键取值（addedAt 键无行情语义，恒非 null——null 标志前置对其直通）。 */
    private static BigDecimal sortValueOf(WatchlistItemPagedRow row, String sort) {
        return switch (sort) {
            case "price" -> row.price();
            case "changePct" -> row.changePct();
            default -> BigDecimal.ONE;
        };
    }

    /** 行情 map 取数值列（FieldMapper to_decimal 产物为 BigDecimal；对 Number/String 兜底兼容）。 */
    private static BigDecimal decimalOf(Map<String, Object> quote, String key) {
        if (quote == null) {
            return null;
        }
        Object value = quote.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 创建清单；同名→30011/409（幂等：重复请求不产生重复行）。 */
    @Transactional
    public WatchlistView createWatchlist(String name, String remark) {
        long userId = currentUserId();
        if (repository.existsByOwnerIdAndName(userId, name)) {
            log.info("创建清单同名冲突（幂等拦截）: userId={}, name={}", userId, name);
            throw new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST, "清单名已存在");
        }
        Watchlist saved = repository.save(Watchlist.create(userId, name, remark));
        log.info("创建清单成功: id={}, userId={}, name={}", saved.getId(), userId, name);
        return WatchlistView.from(saved);
    }

    /**
     * 改名；清单不存在→30010/404，越权→30012/403，与他人启用清单同名→30011/409（幂等语义同创建）。
     *
     * <p>改名 = 自身当前名 → 幂等成功直接返回（不误报同名冲突）。
     */
    @Transactional
    public WatchlistView renameWatchlist(Long watchlistId, String name) {
        long userId = currentUserId();
        Watchlist watchlist = loadOwned(userId, watchlistId);
        if (watchlist.getName().equals(name)) {
            // 改名 = 当前名：幂等成功，无变更不落库
            return WatchlistView.from(watchlist);
        }
        if (repository.existsByOwnerIdAndName(userId, name)) {
            log.info("改名同名冲突（幂等拦截）: userId={}, watchlistId={}, name={}", userId, watchlistId, name);
            throw new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST, "清单名已存在");
        }
        watchlist.rename(name);
        Watchlist saved = repository.save(watchlist);
        log.info("改名清单成功: id={}, name={}", saved.getId(), saved.getName());
        return WatchlistView.from(saved);
    }

    /**
     * 删除清单（软删除 status→0）；清单不存在→30010/404，越权→30012/403。
     *
     * <p>软删除后：启用查询（findAll/findByOwnerIdAndId）与异动任务（JOIN w.status=1）自然排除； 清单项行保留供审计追溯，不释放
     * watchlist_item 的 UNIQUE（清单已不可达，无重加冲突面）。
     */
    @Transactional
    public void deleteWatchlist(Long watchlistId) {
        long userId = currentUserId();
        Watchlist watchlist = loadOwned(userId, watchlistId);
        watchlist.delete();
        repository.save(watchlist);
        log.info(
                "删除清单成功（软删除）: id={}, userId={}, items={}",
                watchlistId,
                userId,
                watchlist.getItems().size());
    }

    /** 行级装载启用清单：不存在→30010/404，存在但非本人/已删→30012/403。 */
    private Watchlist loadOwned(long userId, Long watchlistId) {
        if (!repository.existsById(watchlistId)) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
        return repository
                .findByOwnerIdAndId(userId, watchlistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN));
    }

    /**
     * 加标的到清单；清单不存在→30010/404、越权→30012/403、标的不存在→30001/404、已在清单→30011/409（幂等）。
     *
     * @param anomalyThreshold 异动阈值，null 走默认 3.00（与 DDL DEFAULT 对齐）
     */
    @Transactional
    public WatchlistItemView addItem(
            Long watchlistId, Long subjectId, BigDecimal anomalyThreshold) {
        long userId = currentUserId();
        ensureOwned(userId, watchlistId);
        if (subjectRepository.findById(subjectId).isEmpty()) {
            log.info("加标的失败：标的不存在 watchlistId={}, subjectId={}", watchlistId, subjectId);
            throw new BusinessException(ErrorCode.SUBJECT_NOT_FOUND);
        }
        if (repository.existsItemByWatchlistAndSubject(watchlistId, subjectId)) {
            log.info("加标的重复（幂等拦截）: watchlistId={}, subjectId={}", watchlistId, subjectId);
            throw new BusinessException(ErrorCode.SUBJECT_ALREADY_IN_WATCHLIST);
        }
        WatchlistItem saved =
                repository.saveItem(WatchlistItem.create(watchlistId, subjectId, anomalyThreshold));
        log.info(
                "加标的成功: id={}, watchlistId={}, subjectId={}",
                saved.getId(),
                watchlistId,
                subjectId);
        return WatchlistItemView.from(saved);
    }

    /** 移除清单项（幂等：已不存在不报错，行级校验清单归属）。 */
    @Transactional
    public void removeItem(Long watchlistId, Long itemId) {
        long userId = currentUserId();
        ensureOwned(userId, watchlistId);
        boolean deleted = repository.deleteItemByWatchlistAndItemId(watchlistId, itemId);
        log.info("移除清单项: watchlistId={}, itemId={}, deleted={}", watchlistId, itemId, deleted);
    }

    /** 改异动阈值；清单不存在→30010/404、越权→30012/403、清单项不存在→30010/404。 */
    @Transactional
    public WatchlistItemView updateItemThreshold(
            Long watchlistId, Long itemId, BigDecimal anomalyThreshold) {
        long userId = currentUserId();
        ensureOwned(userId, watchlistId);
        WatchlistItem item =
                repository
                        .findItemByWatchlistAndItemId(watchlistId, itemId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND));
        item.updateThreshold(anomalyThreshold); // 领域行为：非空非负校验
        WatchlistItem saved = repository.saveItem(item); // @Version 乐观锁生效
        log.info("改阈值成功: itemId={}, threshold={}", itemId, saved.getAnomalyThreshold());
        return WatchlistItemView.from(saved);
    }

    /** 行级校验清单归属：清单不存在→30010/404，存在但非本人→30012/403。 */
    private void ensureOwned(long userId, Long watchlistId) {
        if (!repository.existsById(watchlistId)) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
        if (!repository.existsByOwnerIdAndId(userId, watchlistId)) {
            throw new BusinessException(ErrorCode.WATCHLIST_FORBIDDEN);
        }
    }

    private static long currentUserId() {
        UserContext.Principal principal = UserContext.get();
        if (principal == null) {
            // 不应发生：受保护接口经 JwtAuthFilter 已写入 UserContext；防御性 fail-fast
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "未认证上下文");
        }
        return principal.userId();
    }
}
