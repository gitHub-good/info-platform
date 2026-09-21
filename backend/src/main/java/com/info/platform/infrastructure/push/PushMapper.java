package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * push_record 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}；游标分页/待推查询用 LambdaQueryWrapper。
 */
@Mapper
public interface PushMapper extends BaseMapper<PushRecordPO> {}
