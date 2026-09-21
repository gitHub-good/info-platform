package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** data_source_event 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface DataSourceEventMapper extends BaseMapper<DataSourceEventPO> {}
