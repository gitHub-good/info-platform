package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 增量重评配置键校验器（M22 T190，方案 §3.5-1 {@code incremental.reeval} 保存侧防御）：minScoreGap 0~10 /
 * linkMinIntervalMinutes 0~60 / scanWindowHours 1~72 / eventBufferSeconds 0~120 / minImportance ∈
 * {HIGH, MEDIUM, LOW}——非法抛 30092 INCREMENTAL_REEVAL_CONFIG_INVALID（msg 字段级，多问题 "; " 连接），DB
 * 原值保留继续生效。
 *
 * <p>类型判定单一事实源在此（ValuationConfigValidator 同因）：字符串数字不静默采信。读侧第二道防御见 {@link
 * IncrementalReevalSettings}（字段级回退缺省）。
 */
@Component
public class IncrementalReevalConfigValidator implements RuntimeConfigValidator {

    /** 增量重评配置键（与 IncrementalReevalSettings 读取键同源）。 */
    public static final String CONFIG_KEY = IncrementalReevalSettings.CONFIG_KEY;

    @Override
    public boolean supports(String configKey) {
        return CONFIG_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        requireDecimal(
                document,
                "minScoreGap",
                IncrementalReevalSettings.MIN_SCORE_GAP_MIN,
                IncrementalReevalSettings.MIN_SCORE_GAP_MAX,
                problems);
        requireInt(
                document,
                "linkMinIntervalMinutes",
                IncrementalReevalSettings.LINK_INTERVAL_MIN,
                IncrementalReevalSettings.LINK_INTERVAL_MAX,
                problems);
        requireInt(
                document,
                "scanWindowHours",
                IncrementalReevalSettings.SCAN_WINDOW_MIN,
                IncrementalReevalSettings.SCAN_WINDOW_MAX,
                problems);
        requireInt(
                document,
                "eventBufferSeconds",
                IncrementalReevalSettings.BUFFER_SECONDS_MIN,
                IncrementalReevalSettings.BUFFER_SECONDS_MAX,
                problems);
        requireImportance(document, "minImportance", problems);
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INCREMENTAL_REEVAL_CONFIG_INVALID, String.join("; ", problems));
        }
    }

    private static void requireImportance(JsonNode doc, String field, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
            return;
        }
        if (!node.isTextual() || Importance.fromName(node.asText()) == null) {
            problems.add(field + ": 须为 HIGH / MEDIUM / LOW 枚举值");
        }
    }

    private static void requireDecimal(
            JsonNode doc, String field, double min, double max, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
            return;
        }
        if (!node.isNumber()) {
            problems.add(field + ": 须为数值");
            return;
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            problems.add(field + ": 须在 " + min + " ~ " + max + " 范围内");
        }
    }

    private static void requireInt(
            JsonNode doc, String field, int min, int max, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
            return;
        }
        if (!node.isIntegralNumber()) {
            problems.add(field + ": 须为整数");
            return;
        }
        int value = node.asInt();
        if (value < min || value > max) {
            problems.add(field + ": 须在 " + min + " ~ " + max + " 范围内");
        }
    }
}
