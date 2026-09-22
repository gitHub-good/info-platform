package com.info.platform.application.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code aggregation.global} 键的校验器（公共域，T34）。
 *
 * <p>键空间（方案 §4.1）：{@code detailTimeoutMillis(500~10000)}——聚合编排总超时下限防「零超时即失败」、上限防前端
 * 请求超时内无法返回的滥用值。本批唯一注册的公共校验器，同时作为各域校验器（T35~T37）的表驱动样例。
 */
@Component
public class AggregationGlobalConfigValidator implements RuntimeConfigValidator {

    /** 键名（与其他组件共用的常量位；各域键前缀常量随 T35~T37 落到各自域内）。 */
    public static final String KEY = "aggregation.global";

    private static final List<ConfigFieldRules.FieldRule> RULES =
            List.of(ConfigFieldRules.longRange("detailTimeoutMillis", 500, 10000));

    @Override
    public boolean supports(String configKey) {
        return KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        ConfigFieldRules.enforce(document, RULES);
    }
}
