package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link WatchlistRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>行级权限：所有返回清单/清单项数据的查询均 {@code WHERE user_id=?}（ownerUserId）或 {@code WHERE
 * watchlist_id=?}（清单项归属已校验的清单），端口层即约束——用户只能读到自己的清单。 唯一不带 owner 过滤的是 {@link
 * #existsById}，仅返回布尔存在性，供应用层区分 404/403。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 文本；清单项在读取清单时一并装载（ findAll 按 watchlist_id IN(...) 批量取，避免
 * N+1）；乐观锁由 {@code @Version} + {@code OptimisticLockerInnerInterceptor} 守护。
 */
@Repository
public class WatchlistRepositoryImpl implements WatchlistRepository {

    private static final Logger log = LoggerFactory.getLogger(WatchlistRepositoryImpl.class);

    private final WatchlistMapper watchlistMapper;
    private final WatchlistItemMapper itemMapper;

    public WatchlistRepositoryImpl(
            WatchlistMapper watchlistMapper, WatchlistItemMapper itemMapper) {
        this.watchlistMapper = watchlistMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public Optional<Watchlist> findByOwnerIdAndId(long ownerUserId, Long id) {
        WatchlistPO po =
                watchlistMapper.selectOne(
                        new LambdaQueryWrapper<WatchlistPO>()
                                .eq(WatchlistPO::getUserId, ownerUserId)
                                .eq(WatchlistPO::getId, id)
                                .eq(WatchlistPO::getStatus, WatchlistStatus.ENABLED.code()));
        if (po == null) {
            return Optional.empty();
        }
        List<WatchlistItem> items =
                loadItems(Collections.singletonList(po.getId()))
                        .getOrDefault(po.getId(), Collections.emptyList());
        return Optional.of(toEntity(po, items));
    }

    @Override
    public boolean existsByOwnerIdAndId(long ownerUserId, Long id) {
        return watchlistMapper.exists(
                new LambdaQueryWrapper<WatchlistPO>()
                        .eq(WatchlistPO::getUserId, ownerUserId)
                        .eq(WatchlistPO::getId, id)
                        .eq(WatchlistPO::getStatus, WatchlistStatus.ENABLED.code()));
    }

    @Override
    public List<Watchlist> findAllByOwnerId(long ownerUserId) {
        List<WatchlistPO> pos =
                watchlistMapper.selectList(
                        new LambdaQueryWrapper<WatchlistPO>()
                                .eq(WatchlistPO::getUserId, ownerUserId)
                                .eq(WatchlistPO::getStatus, WatchlistStatus.ENABLED.code())
                                .orderByAsc(WatchlistPO::getId));
        if (pos.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = pos.stream().map(WatchlistPO::getId).collect(Collectors.toList());
        Map<Long, List<WatchlistItem>> byWatchlist = loadItems(ids);
        List<Watchlist> result = new ArrayList<>(pos.size());
        for (WatchlistPO po : pos) {
            result.add(toEntity(po, byWatchlist.getOrDefault(po.getId(), Collections.emptyList())));
        }
        return result;
    }

    @Override
    public boolean existsByOwnerIdAndName(long ownerUserId, String name) {
        return watchlistMapper.exists(
                new LambdaQueryWrapper<WatchlistPO>()
                        .eq(WatchlistPO::getUserId, ownerUserId)
                        .eq(WatchlistPO::getName, name)
                        .eq(WatchlistPO::getStatus, WatchlistStatus.ENABLED.code()));
    }

    @Override
    public boolean existsById(Long id) {
        return watchlistMapper.exists(
                new LambdaQueryWrapper<WatchlistPO>().eq(WatchlistPO::getId, id));
    }

    @Override
    @Transactional
    public Watchlist save(Watchlist watchlist) {
        WatchlistPO po = toPO(watchlist);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getVersion() == null) {
                po.setVersion(0);
            }
            watchlistMapper.insert(po);
            log.info("新增自选清单: id={}, userId={}, name={}", po.getId(), po.getUserId(), po.getName());
            return toEntity(po, Collections.emptyList());
        }
        po.setUpdatedAt(now);
        watchlistMapper.updateById(po);
        log.info("更新自选清单: id={}, version={}", po.getId(), po.getVersion());
        return toEntity(po, watchlist.getItems());
    }

    @Override
    public boolean existsItemByWatchlistAndSubject(Long watchlistId, Long subjectId) {
        return itemMapper.exists(
                new LambdaQueryWrapper<WatchlistItemPO>()
                        .eq(WatchlistItemPO::getWatchlistId, watchlistId)
                        .eq(WatchlistItemPO::getSubjectId, subjectId)
                        .eq(WatchlistItemPO::getStatus, WatchlistStatus.ENABLED.code()));
    }

    @Override
    public Optional<WatchlistItem> findItemByWatchlistAndItemId(Long watchlistId, Long itemId) {
        WatchlistItemPO po =
                itemMapper.selectOne(
                        new LambdaQueryWrapper<WatchlistItemPO>()
                                .eq(WatchlistItemPO::getWatchlistId, watchlistId)
                                .eq(WatchlistItemPO::getId, itemId)
                                .eq(WatchlistItemPO::getStatus, WatchlistStatus.ENABLED.code()));
        return Optional.ofNullable(po).map(WatchlistRepositoryImpl::toItemEntity);
    }

    @Override
    @Transactional
    public WatchlistItem saveItem(WatchlistItem item) {
        WatchlistItemPO po = toItemPO(item);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getVersion() == null) {
                po.setVersion(0);
            }
            itemMapper.insert(po);
            log.info(
                    "新增清单项: id={}, watchlistId={}, subjectId={}",
                    po.getId(),
                    po.getWatchlistId(),
                    po.getSubjectId());
        } else {
            po.setUpdatedAt(now);
            itemMapper.updateById(po); // @Version 乐观锁生效
            log.info("更新清单项: id={}, watchlistId={}", po.getId(), po.getWatchlistId());
        }
        return toItemEntity(po);
    }

    @Override
    @Transactional
    public boolean deleteItemByWatchlistAndItemId(Long watchlistId, Long itemId) {
        int rows =
                itemMapper.delete(
                        new LambdaQueryWrapper<WatchlistItemPO>()
                                .eq(WatchlistItemPO::getWatchlistId, watchlistId)
                                .eq(WatchlistItemPO::getId, itemId));
        if (rows > 0) {
            log.info("移除清单项: watchlistId={}, itemId={}", watchlistId, itemId);
        }
        return rows > 0;
    }

    /** 批量按 watchlist_id 加载清单项并分组（避免 N+1；空集合直接返回空 map）。 */
    private Map<Long, List<WatchlistItem>> loadItems(List<Long> watchlistIds) {
        if (watchlistIds == null || watchlistIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<WatchlistItemPO> pos =
                itemMapper.selectList(
                        new LambdaQueryWrapper<WatchlistItemPO>()
                                .in(WatchlistItemPO::getWatchlistId, watchlistIds)
                                .eq(WatchlistItemPO::getStatus, WatchlistStatus.ENABLED.code())
                                .orderByAsc(WatchlistItemPO::getId));
        Map<Long, List<WatchlistItem>> grouped = new HashMap<>();
        for (WatchlistItemPO po : pos) {
            grouped.computeIfAbsent(po.getWatchlistId(), k -> new ArrayList<>())
                    .add(toItemEntity(po));
        }
        return grouped;
    }

    private static Watchlist toEntity(WatchlistPO po, List<WatchlistItem> items) {
        return Watchlist.reconstruct(
                po.getId(),
                po.getUserId(),
                po.getName(),
                po.getRemark(),
                WatchlistStatus.fromCode(po.getStatus()),
                items,
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static WatchlistPO toPO(Watchlist watchlist) {
        WatchlistPO po = new WatchlistPO();
        po.setId(watchlist.getId());
        po.setUserId(watchlist.getUserId());
        po.setName(watchlist.getName());
        po.setRemark(watchlist.getRemark());
        po.setStatus(watchlist.getStatus().code());
        po.setVersion((int) watchlist.getVersion());
        po.setCreatedAt(
                watchlist.getCreatedAt() == null ? null : watchlist.getCreatedAt().toString());
        po.setUpdatedAt(
                watchlist.getUpdatedAt() == null ? null : watchlist.getUpdatedAt().toString());
        return po;
    }

    private static WatchlistItem toItemEntity(WatchlistItemPO po) {
        return WatchlistItem.reconstruct(
                po.getId(),
                po.getWatchlistId(),
                po.getSubjectId(),
                po.getAnomalyThreshold(),
                WatchlistStatus.fromCode(po.getStatus()),
                po.getVersion() == null ? 0L : po.getVersion().longValue(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static WatchlistItemPO toItemPO(WatchlistItem item) {
        WatchlistItemPO po = new WatchlistItemPO();
        po.setId(item.getId());
        po.setWatchlistId(item.getWatchlistId());
        po.setSubjectId(item.getSubjectId());
        po.setAnomalyThreshold(item.getAnomalyThreshold());
        po.setStatus(item.getStatus().code());
        po.setVersion((int) item.getVersion());
        po.setCreatedAt(item.getCreatedAt() == null ? null : item.getCreatedAt().toString());
        po.setUpdatedAt(item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString());
        return po;
    }
}
