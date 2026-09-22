package com.info.platform.infrastructure.policy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** policy_item 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface PolicyMapper extends BaseMapper<PolicyItemPO> {}
