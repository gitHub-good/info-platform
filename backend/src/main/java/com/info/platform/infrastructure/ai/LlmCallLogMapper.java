package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** llm_call_log 表的 MyBatis-Plus Mapper（T30）。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLogPO> {}
