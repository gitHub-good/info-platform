package com.info.platform.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.ConfigFieldRules.FieldRule;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * LLM 域运行时配置校验器（T35，键空间规则见方案 §4.1 / UI 方案 §5.3）。
 *
 * <p>覆盖 {@code llm.global} 与 {@code llm.provider.*} 两类键，规则用 {@link ConfigFieldRules} 工厂表组装： 单价
 * ≥0、预算 &gt;0、告警阈值 (0,1]、超时/TTL 正整数、retry 0~3、model 非空、baseUrl http(s)。 跨字段规则（DEFECT-3， 方案
 * §4.1「fallback 须为存在且 enabled 的 provider」）：① fallback 不得指向自身、须为已知 provider 且目标当前 <b>启用</b>（指向停用目标会让
 * fallback 链静默失去灾备保护——运行时网关按 enabled 跳过，用户无感知）； ② 停用被启用中 provider 引用为 fallback 的 provider 时明确拒绝（单
 * provider 独立 PUT 无法原子改两条记录， 提示先解除引用即闭环）；引用方自身停用的休眠引用不阻塞。
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

    /**
     * 惰性解析的配置服务：运行时键是交叉校验（fallback 目标 enabled、被引用状态）的读取源。 须 {@link ObjectProvider} 惰性注入——{@link
     * RuntimeConfigService} 构造持有全部校验器，直接注入成环。
     */
    private final ObjectProvider<RuntimeConfigService> configServiceProvider;

    public LlmConfigValidator(
            LlmConfig llmConfig, ObjectProvider<RuntimeConfigService> configServiceProvider) {
        this.llmConfig = llmConfig;
        this.configServiceProvider = configServiceProvider;
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
        checkDisableReferencedByEnabledFallback(configKey, document);
        ConfigFieldRules.enforce(document, PROVIDER_RULES);
    }

    /**
     * fallback 须为已知 provider（本期不增删条目，已知集 = yml 配置名）、不得指向自身、 且目标当前须为启用状态（DEFECT-3 复现 a：指向停用目标 =
     * 静默失去灾备保护）。目标 enabled 读当前生效配置（运行时键 &gt; yml 缺省）。
     */
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
        if (!effectiveEnabled(target)) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "fallback: 须为启用的 provider，目标 "
                            + target
                            + " 当前为停用状态（先启用 "
                            + target
                            + " 或改指其他启用的 provider）");
        }
    }

    /**
     * 停用被引用 provider 的处置（DEFECT-3 复现 b）：合并后 enabled=false 时，若仍被<b>启用中</b>的其他 provider 引用为
     * fallback，明确拒绝并提示先解除引用（合理闭环——单 provider 独立 PUT 无法原子改两条记录）。 仅被停用 provider 引用（休眠引用）不阻塞：运行时链不经过停用
     * provider，无灾备损失，不扩大拒绝面。
     */
    private void checkDisableReferencedByEnabledFallback(String configKey, JsonNode document) {
        if (document.path("enabled").asBoolean(false)) {
            return;
        }
        String self = configKey.substring(ConfigCenter.KEY_LLM_PROVIDER_PREFIX.length());
        List<String> referencing =
                llmConfig.getProviders().stream()
                        .map(LlmConfig.Provider::getName)
                        .filter(name -> !name.equals(self))
                        .filter(this::effectiveEnabled)
                        .filter(name -> self.equals(effectiveFallback(name)))
                        .toList();
        if (!referencing.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.PARAM_INVALID,
                    "enabled: "
                            + self
                            + " 已被启用中的 provider "
                            + referencing
                            + " 引用为 fallback，请先解除引用（改指其他启用的 provider）再停用");
        }
    }

    /** 当前生效 enabled：运行时键文档 &gt; yml 缺省（键缺失 = 未写入/种子前）。 */
    private boolean effectiveEnabled(String name) {
        return configServiceProvider
                .getObject()
                .read(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name)
                .map(entry -> entry.document().path("enabled").asBoolean(false))
                .orElseGet(
                        () ->
                                llmConfig.providerByName(name) != null
                                        && llmConfig.providerByName(name).isEnabled());
    }

    /** 当前生效 fallback（运行时键文档 &gt; yml 缺省；未配置/空白返回 null）。 */
    private String effectiveFallback(String name) {
        java.util.Optional<RuntimeConfigEntry> entry =
                configServiceProvider.getObject().read(ConfigCenter.KEY_LLM_PROVIDER_PREFIX + name);
        if (entry.isPresent()) {
            String value = entry.orElseThrow().document().path("fallback").asText(null);
            return value == null || value.isBlank() ? null : value;
        }
        LlmConfig.Provider yml = llmConfig.providerByName(name);
        return yml == null ? null : yml.getFallback();
    }

    /** 可选规则升为必填（种子始终写入的字段，缺行/缺字段即配置面异常）。 */
    private static FieldRule required(FieldRule rule) {
        return new FieldRule(rule.field(), true, rule.checker(), rule.violation());
    }
}
