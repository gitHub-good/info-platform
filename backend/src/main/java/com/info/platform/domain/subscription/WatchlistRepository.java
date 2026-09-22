package com.info.platform.domain.subscription;

import java.util.List;
import java.util.Optional;

/**
 * 自选清单仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>行级权限</h2>
 *
 * 数据查询方法均带 {@code ownerUserId} 参数，基础设施层在 SQL {@code WHERE user_id=?} 过滤， 端口层即约束行级权限——应用层取 {@code
 * UserContext.get().userId()} 传入。 唯一不带 {@code ownerUserId} 的是 {@link #existsById}，
 * 仅返回布尔存在性（不泄露其他用户清单数据）， 供应用层区分「清单不存在→30010(404)」与「清单存在但非本人→30012(403)」。
 *
 * <h2>幂等</h2>
 *
 * 按 §4.4「幂等业务语义键」：创建清单靠 {@link #existsByOwnerIdAndName} 查重（键=userId+name）； 加标的靠 {@link
 * #existsItemByWatchlistAndSubject} 查重（键=userId+watchlistId+subjectId）， DB {@code
 * UNIQUE(watchlist_id, subject_id)} 为最后防线。
 */
public interface WatchlistRepository {

    /** 按归属用户+清单 id 加载清单（含清单项），仅返回本人清单。 */
    Optional<Watchlist> findByOwnerIdAndId(long ownerUserId, Long id);

    /** 按归属用户+清单 id 判定本人清单是否存在（行级校验用，不装载清单项）。 */
    boolean existsByOwnerIdAndId(long ownerUserId, Long id);

    /** 按归属用户加载全部启用清单（含清单项）。 */
    List<Watchlist> findAllByOwnerId(long ownerUserId);

    /** 同名清单查重（创建幂等键=userId+name）。 */
    boolean existsByOwnerIdAndName(long ownerUserId, String name);

    /** 清单 id 是否存在（404/403 区分用，不按归属过滤，仅返回布尔）。 */
    boolean existsById(Long id);

    /** 落库：id 为空走 INSERT 并回填主键，非空走 UPDATE（乐观锁由基础设施层处理）。 */
    Watchlist save(Watchlist watchlist);

    /** 清单项查重（加标的幂等键=userId+watchlistId+subjectId）。 */
    boolean existsItemByWatchlistAndSubject(Long watchlistId, Long subjectId);

    /** 按清单+清单项 id 加载清单项（改阈值/移除时定位，过滤 watchlistId）。 */
    Optional<WatchlistItem> findItemByWatchlistAndItemId(Long watchlistId, Long itemId);

    /** 落库清单项：id 为空走 INSERT 并回填主键，非空走 UPDATE。 */
    WatchlistItem saveItem(WatchlistItem item);

    /** 物理删除清单项（释放 UNIQUE 约束便于重新加标的）；返回是否实际删除。 */
    boolean deleteItemByWatchlistAndItemId(Long watchlistId, Long itemId);

    /**
     * 查询全部活跃清单项（跨全部用户，扁平清单项视图）。
     *
     * <p><b>系统任务专用，不走行级权限</b>：供 {@code AnomalyDetectionJob}（FIXED_DELAY 异动检测轮询）遍历所有用户的
     * 启用清单项。只返回处于启用清单（watchlist.status=1）且自身启用（watchlist_item.status=1）的项；
     * 已删除清单下的项不参与异动检测。与面向用户的查询（带 ownerUserId）正交，无越权风险——本方法不暴露清单归属信息， 仅返回清单项的标的与阈值。
     *
     * @return 活跃清单项列表（按 id 升序）
     */
    List<WatchlistItem> findAllActiveItems();
}
