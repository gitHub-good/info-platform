package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** anomaly_event 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface AnomalyMapper extends BaseMapper<AnomalyRecordPO> {}
