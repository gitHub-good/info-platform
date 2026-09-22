package com.info.platform.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.ConfigFieldRules.FieldRule;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * LLM 域运行时配置校验器（T35，键空间规则见方案 §4.1 / UI 方案 §5.3）。
 *
 * <p>覆盖 {@code llm.global} 与 {@code llm.provider.*} 两类键，规则用 {@link ConfigFieldRules} 工厂表组装： 单价
 * ≥0、预算 &gt;0、告警阈值 (0,1]、超时/TTL 正整数、retry 0~3、model 非空、baseUrl http(s)。 跨字段规则：fallback 须为已知
 * provider 且不得指向自身（fallback 目标的 enabled 不在写入时强校验—— 单 provider 独立保存无法原子改两条记录，运行时网关对链中已停用 provider
 * 本就跳过，语义等价不阻塞启停操作）。
 *
 * <p>校验对象为「当前文档合并请求字段后」的完整文档（部分更新由 {@code LlmConfigFacadeImpl} 先合并再落库）， 故种子始终写入的字段按必填校验。
 */
@Component
public class LlmConfigValidator implements RuntimeConfigValidator {

    private static final List<FieldRule> GLOBAL_RULES =
            List.of(
                    required(ConfigFieldRules.positiveLong("timeoutSeconds")),
                    required(ConfigFieldRules.longRange("retry", 0, 3)),
                    required(ConfigFieldRules.positiveLong("dailyTokenBudgetPerUser")),
                    required(ConfigFieldRules.decimalRange("budgetWarnRatio", 0, 1)),
                    required(ConfigFieldRules.positiveLong("cacheDefaultTtlSeconds")),
                    ConfigFieldRules.positiveLongMap("cacheTtlSeconds"));

    private static final List<FieldRule> PROVIDER_RULES =
            List.of(
                    required(ConfigFieldRules.nonBlank("model")),
                    required(ConfigFieldRules.bool("enabled")),
                    required(ConfigFieldRules.bool("isDefault")),
                    ConfigFieldRules.nonBlank("fallback"),
                    ConfigFieldRules.nonNegativeDecimal("inputPricePerMillion"),
                    ConfigFieldRules.nonNegativeDecimal("outputPricePerMillion"),
                    ConfigFieldRules.url("baseUrl"),
                    // key 密文字段可选（种子不导入 key，ADR-0018：key 只走环境变量或页面录入）
                    ConfigFieldRules.nonBlank("apiKeyCipher"),
                    ConfigFieldRules.nonBlank("apiKeyLast4"));

    private final LlmConfig llmConfig;

    public LlmConfigValidator(LlmConfig llmConfig) {
        this.llmConfig = llmConfig;
    }

    @Override
    public boolean supports(String configKey) {
        return ConfigCenter.KEY_LLM_GLOBAL.equals(configKey)
                || configKey != null && configKey.startsWith(ConfigCenter.KEY_LLM_PROVIDER_PREFIX);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        if (ConfigCenter.KEY_LLM_GLOBAL.equals(configKey)) {
            ConfigFieldRules.enforce(document, GLOBAL_RULES);
            return;
        }
        // 跨字段规则先于字段表执行：字段表首错抛出会短路跨字段校验，fallback 非法值会漏到下一次提交才暴露
        checkFallback(configKey, document);
        ConfigFieldRules.enforce(document, PROVIDER_RULES);
    }

    /** fallback 须为已知 provider（本期不增删条目，已知集 = yml 配置名）且不得指向自身。 */
    private void checkFallback(String configKey, JsonNode document) {
        JsonNode fallback = document.get("fallback");
        if (fallback == null || fallback.isNull() || fallback.asText().isBlank()) {
            return;
        }
        String self = configKey.substring(ConfigCenter.KEY_LLM_PROVIDER_PREFIX.length());
        String target = fallback.asText();
        Set<String> known =
                llmConfig.getProviders().stream()
                        .map(LlmConfig.Provider::getName)
                        .collect(Collectors.toSet());
        if (target.equals(self)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "fallback: 不得指向自身（" + self + "）");
        }
        if (!known.contains(target)) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "fallback: 须为已存在的 provider，当前值=" + target + "（已知: " + known + "）");
        }
    }

    /** 可选规则升为必填（种子始终写入的字段，缺行/缺字段即配置面异常）。 */
    private static FieldRule required(FieldRule rule) {
        return new FieldRule(rule.field(), true, rule.checker(), rule.violation());
    }
}
