package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** {@code user} 表的 MyBatis-Plus Mapper。现仅用 BaseMapper，复杂查询后续按需扩展。 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {}
