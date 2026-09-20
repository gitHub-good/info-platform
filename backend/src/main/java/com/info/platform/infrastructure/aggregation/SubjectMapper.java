package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * subject_master 的 MyBatis-Plus Mapper。复杂查询后续写 XML，现仅用 BaseMapper。
 */
@Mapper
public interface SubjectMapper extends BaseMapper<SubjectPO> {
}
