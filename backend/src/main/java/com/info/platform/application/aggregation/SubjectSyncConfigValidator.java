package com.info.platform.application.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.aggregation.SourceProviders;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 标的池同步域运行时配置校验器（ADR-0032，键空间 {@code subject.sync}；ADR-0033 增降级链字段）。
 *
 * <p>字段：{@code fallbackChain} 可选字符串数组（A 股列表桶降级链，非空时成员 ∈ {eastmoney | sina} 且无重复， 空 =
 * 仅主源，缺失回落旧键折算/注册表全链兜底）；{@code aShareSource} 可选枚举（auto | eastmoney | sina， ADR-0033
 * 起为<b>读取兼容口径</b>——写路径统一 {@code fallbackChain}，旧键逐步淘汰）。 消费点 {@code RoutingSubjectListSource}
 * 用时读取（LIVE 级，保存即热生效）；非法取值在此拦截，读取侧对 DB 手改坏值另有 WARN 回落全链兜底。
 */
@Component
public class SubjectSyncConfigValidator implements RuntimeConfigValidator {

    /** 域键（种子/消费点/校验三方共用）。 */
    public static final String KEY = "subject.sync";

    @Override
    public boolean supports(String configKey) {
        return KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>(chainProblems(document.get("fallbackChain")));
        try {
            // enforce 直接抛 2001 PARAM_INVALID（字段级原因），此处无需转译
            ConfigFieldRules.enforce(
                    document,
                    List.of(ConfigFieldRules.oneOf("aShareSource", "auto", "eastmoney", "sina")));
        } catch (BusinessException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }

    /** 降级链校验（ADR-0033）：可选；存在时须为字符串数组，非空时成员 ∈ A 股列表注册表且无重复（空 = 仅主源）。 */
    private static List<String> chainProblems(JsonNode chain) {
        if (chain == null || chain.isNull()) {
            return List.of();
        }
        if (!chain.isArray()) {
            return List.of("fallbackChain: 须为字符串数组");
        }
        if (chain.isEmpty()) {
            return List.of();
        }
        List<String> allowed = SourceProviders.A_SHARE_LIST_PROVIDERS;
        Set<String> seen = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (JsonNode item : chain) {
            if (!item.isTextual()) {
                problems.add("fallbackChain: 成员须为字符串");
                continue;
            }
            String provider = item.asText();
            if (!allowed.contains(provider)) {
                problems.add(
                        "fallbackChain: '"
                                + provider
                                + "' 不在 A 股列表源可用 provider（"
                                + String.join("|", allowed)
                                + "）");
            }
            if (!seen.add(provider)) {
                problems.add("fallbackChain: 重复 provider '" + provider + "'");
            }
        }
        return problems;
    }
}
