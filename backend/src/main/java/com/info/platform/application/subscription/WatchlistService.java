package com.info.platform.application.subscription;

import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.math.BigDecimal;
import java.util.List;
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

    public WatchlistService(WatchlistRepository repository, SubjectRepository subjectRepository) {
        this.repository = repository;
        this.subjectRepository = subjectRepository;
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
