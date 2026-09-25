package com.info.platform.application.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.retention.RetentionLogTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * retention 域校验器（T72，方案 §4.2 保存侧防御）：{@code retention.global} 四字段必填整型、逐字段 ≥ 表下限
 * （无上限——改大=少删，方向安全）。表驱动按 {@link RetentionLogTable} 枚举遍历，下限取枚举常量（单一事实源）。
 *
 * <p>非法值抛 2001 PARAM_INVALID，msg 带字段级原因（多字段以 "; " 连接，对齐 JobRuntimeConfigValidator 惯例），DB
 * 原值保留继续生效。未知多余字段不拒绝（读侧忽略）。执行侧的第二道防御见 {@link RetentionWindows}（字段级回退）。
 */
@Component
public class RetentionConfigValidator implements RuntimeConfigValidator {

    /** 窗口配置键（与 RetentionCleanupService.CONFIG_KEY 同键）。 */
    public static final String CONFIG_KEY = "retention.global";

    @Override
    public boolean supports(String configKey) {
        return CONFIG_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        for (RetentionLogTable table : RetentionLogTable.values()) {
            JsonNode field = document.get(table.jsonField());
            if (field == null || field.isNull()) {
                problems.add(table.jsonField() + ": 必填");
            } else if (!field.isIntegralNumber()) {
                problems.add(table.jsonField() + ": 须为整数");
            } else if (field.asInt() < table.minDays()) {
                problems.add(table.jsonField() + ": 须 >= " + table.minDays() + "（" + table.physicalName() + " 下限）");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }
}
