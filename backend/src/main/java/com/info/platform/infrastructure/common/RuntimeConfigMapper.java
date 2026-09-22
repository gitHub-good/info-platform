package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** runtime_config 表的 MyBatis-Plus Mapper（T34）。简单 CRUD 走 {@link BaseMapper}，upsert 语义由仓储实现组装。 */
@Mapper
public interface RuntimeConfigMapper extends BaseMapper<RuntimeConfigPO> {}
