package com.info.platform.application.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.common.BusinessException;

/**
 * 运行时配置校验器（表驱动框架的注册位，T34）。
 *
 * <p>各域校验器实现本接口并注册为 Spring bean，由 {@code RuntimeConfigService.write} 在落库前调用： 先按键匹配（ {@link
 * #supports}，精确键或域前缀均可），再对 JSON 文档整体校验（单字段规则可直接复用 {@link ConfigFieldRules} 表驱动工具；跨字段规则自行实现）。
 *
 * <p>本批（T34）仅注册公共域校验器；llm.provider / datasource / job 域校验器由 T35~T37 注册——未注册键的写入一律拒绝 （2001
 * 未知配置键），即三域读写接口开放前配置面不可写。
 */
public interface RuntimeConfigValidator {

    /** 是否负责该配置键（精确匹配如 "aggregation.global"，或域前缀匹配如 "llm.provider."）。 */
    boolean supports(String configKey);

    /**
     * 校验 JSON 文档；非法值抛 {@link BusinessException}（PARAM_INVALID，msg 带字段级原因，多个以 "; " 连接）。
     *
     * @param configKey 配置键（供跨键校验，如 fallback 须为已存在的 provider）
     * @param document 待写入的 JSON 文档（只读）
     */
    void validate(String configKey, JsonNode document);
}
