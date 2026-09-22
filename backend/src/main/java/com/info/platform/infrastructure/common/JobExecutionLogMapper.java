package com.info.platform.infrastructure.common;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** job_execution_log 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface JobExecutionLogMapper extends BaseMapper<JobExecutionLogPO> {}
