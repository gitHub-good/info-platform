package com.info.platform.application.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.ConfigFieldRules;
import com.info.platform.application.common.RuntimeConfigValidator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 标的池同步域运行时配置校验器（ADR-0032，键空间 {@code subject.sync}）。
 *
 * <p>字段：{@code aShareSource} 可选枚举（auto | eastmoney | sina，缺失回落缺省 auto——部分更新语义）。 消费点 {@code
 * RoutingSubjectListSource} 用时读取（LIVE 级，保存即热生效）；非法取值在此拦截，读取侧对 DB 手改坏值另有 WARN 回落 auto 兜底。
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
        // enforce 直接抛 2001 PARAM_INVALID（字段级原因），此处无需转译
        ConfigFieldRules.enforce(
                document,
                List.of(ConfigFieldRules.oneOf("aShareSource", "auto", "eastmoney", "sina")));
    }
}
