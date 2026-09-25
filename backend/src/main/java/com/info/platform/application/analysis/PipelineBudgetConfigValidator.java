package com.info.platform.application.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 管道护栏预算键校验器（M15 T125，方案 §4.8 {@code pipeline.budget} 保存侧防御）：日预算正整数、两级比例 0~1 且 fuse &gt;
 * degrade、校准值非负整数——热改页面/校准写回共用一道闸。 非法值抛 2001 PARAM_INVALID（msg 字段级），DB 原值保留继续生效；读侧第二道防御见 {@code
 * PipelineSettings}（越界回落缺省）。
 */
@Component
public class PipelineBudgetConfigValidator implements RuntimeConfigValidator {

    static final String CONFIG_KEY = "pipeline.budget";

    @Override
    public boolean supports(String configKey) {
        return CONFIG_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        requirePositiveLong(document, "dailyBudgetMicros", problems);
        double degrade = requireRatio(document, "degradeRatio", problems);
        double fuse = requireRatio(document, "fuseRatio", problems);
        if (degrade > 0 && fuse > 0 && fuse <= degrade) {
            problems.add("fuseRatio: 须大于 degradeRatio（二级熔断阈值高于一级降级）");
        }
        JsonNode calibrated = document.get("calibratedPerItemMicros");
        if (calibrated != null
                && !calibrated.isNull()
                && (!calibrated.isIntegralNumber() || calibrated.asLong() < 0)) {
            problems.add("calibratedPerItemMicros: 须为非负整数（微元）");
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }

    private static void requirePositiveLong(JsonNode doc, String field, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
        } else if (!node.isIntegralNumber() || node.asLong() <= 0) {
            problems.add(field + ": 须为正整数（微元）");
        }
    }

    /**
     * @return 合法比例值（非法/缺失返回 0 供跨字段比较跳过）。
     */
    private static double requireRatio(JsonNode doc, String field, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
            return 0;
        }
        if (!node.isNumber() || node.asDouble() <= 0 || node.asDouble() >= 1) {
            problems.add(field + ": 须为 0~1 开区间比例");
            return 0;
        }
        return node.asDouble();
    }
}
