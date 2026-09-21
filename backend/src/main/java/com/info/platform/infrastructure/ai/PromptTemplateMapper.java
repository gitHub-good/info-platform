package com.info.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** prompt_template 表的 MyBatis-Plus Mapper。简单 CRUD 走 {@link BaseMapper}。 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplatePO> {}
