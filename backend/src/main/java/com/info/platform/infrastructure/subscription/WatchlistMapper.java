package com.info.platform.infrastructure.subscription;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** watchlist 表的 MyBatis-Plus Mapper。复杂查询后续写 XML，现仅用 BaseMapper。 */
@Mapper
public interface WatchlistMapper extends BaseMapper<WatchlistPO> {}
