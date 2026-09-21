package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * watchlist_item 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}；复杂联表查询用 {@code @Select} 原生 SQL
 * （SQLite 支持 JOIN）。
 */
@Mapper
public interface WatchlistItemMapper extends BaseMapper<WatchlistItemPO> {

    /**
     * 查询全部活跃清单项（JOIN watchlist 过滤已删除清单）。
     *
     * <p>供系统级异动检测任务遍历，不带 ownerUserId 过滤（系统任务，无越权风险）。
     *
     * @param itemStatus 清单项状态码（1 启用）
     * @param watchlistStatus 清单状态码（1 启用）
     */
    @Select(
            "SELECT wi.id, wi.watchlist_id, wi.subject_id, wi.anomaly_threshold, wi.status, "
                    + "wi.created_at, wi.updated_at, wi.version "
                    + "FROM watchlist_item wi JOIN watchlist w ON wi.watchlist_id = w.id "
                    + "WHERE wi.status = #{itemStatus} AND w.status = #{watchlistStatus} "
                    + "ORDER BY wi.id")
    List<WatchlistItemPO> selectActiveItems(
            @Param("itemStatus") int itemStatus, @Param("watchlistStatus") int watchlistStatus);

    /**
     * 查询含某标的的全部活跃清单归属用户（去重 user_id），供 T14 推送目标解析（M1 watchlist 隐含异动订阅）。
     *
     * <p>JOIN watchlist 过滤已删除清单（w.status=1）+ 仅启用清单项（wi.status=1）；DISTINCT 去重——
     * 同一用户多清单含同标的只推一次（push_record UNIQUE 兜底再次防重）。
     */
    @Select(
            "SELECT DISTINCT w.user_id "
                    + "FROM watchlist_item wi JOIN watchlist w ON wi.watchlist_id = w.id "
                    + "WHERE wi.subject_id = #{subjectId} AND wi.status = #{itemStatus} "
                    + "AND w.status = #{watchlistStatus}")
    List<Long> selectActiveUserIdsBySubject(
            @Param("subjectId") Long subjectId,
            @Param("itemStatus") int itemStatus,
            @Param("watchlistStatus") int watchlistStatus);
}
