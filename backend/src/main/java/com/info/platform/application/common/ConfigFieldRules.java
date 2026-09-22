package com.info.platform.application.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 表驱动单字段校验规则（公共校验，T34）。
 *
 * <p>键空间表（方案 §4.1）中每条配置的字段规则以 {@link FieldRule} 表达，经 {@link #enforce} 一次性整表校验；
 * 各域校验器（T35~T37）只声明规则表，不重复写校验代码。校验失败统一抛 2001 PARAM_INVALID，msg 带字段级原因（多个以 "; " 连接），对齐
 * PRD「前后端双侧校验」红线。
 *
 * <p>规则语义：<b>字段缺失且 required=true 报错；字段存在才做值校验</b>（部分更新语义由各域校验器自行合并后整体校验）。
 */
public final class ConfigFieldRules {

    private ConfigFieldRules() {}

    /** 单字段规则：字段名 + 缺失是否报错 + 值谓词 + 违规提示。 */
    public record FieldRule(
            String field, boolean required, Predicate<JsonNode> checker, String violation) {

        private String describe(JsonNode value) {
            return field + ": " + violation + "，当前值=" + value.asText();
        }
    }

    /** 整表校验：逐条执行规则，收集全部字段级原因后一次性抛出（不首错即停，便于页面一次标全错误）。 */
    public static void enforce(JsonNode document, List<FieldRule> rules) {
        List<String> problems = new ArrayList<>();
        for (FieldRule rule : rules) {
            JsonNode value = document.get(rule.field());
            if (value == null || value.isNull()) {
                if (rule.required()) {
                    problems.add(rule.field() + ": 必填");
                }
                continue;
            }
            if (!rule.checker().test(value)) {
                problems.add(rule.describe(value));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, String.join("; ", problems));
        }
    }

    // —— 常用规则工厂（对齐方案 §4.1 键空间表与 UI 方案 §5.3 表单校验规范） ——

    /** 正整数（timeoutSeconds / 各类 TTL / intervalMillis 等），可选字段。 */
    public static FieldRule positiveLong(String field) {
        return new FieldRule(field, false, v -> v.isIntegralNumber() && v.asLong() > 0, "须为正整数");
    }

    /** 必填正整数。 */
    public static FieldRule requiredPositiveLong(String field) {
        return new FieldRule(field, true, v -> v.isIntegralNumber() && v.asLong() > 0, "须为正整数");
    }

    /** 闭区间整数（如 retry 0~3、detailTimeoutMillis 500~10000），可选字段。 */
    public static FieldRule longRange(String field, long minInclusive, long maxInclusive) {
        return new FieldRule(
                field,
                false,
                v ->
                        v.isIntegralNumber()
                                && v.asLong() >= minInclusive
                                && v.asLong() <= maxInclusive,
                "须为 " + minInclusive + "~" + maxInclusive + " 的整数");
    }

    /** ≥ 0 的数字（单价，允许 0 免费档），可选字段。 */
    public static FieldRule nonNegativeDecimal(String field) {
        return new FieldRule(field, false, v -> v.isNumber() && v.asDouble() >= 0, "不能为负");
    }

    /** 开闭区间小数（如 budgetWarnRatio ∈ (0,1]），可选字段。 */
    public static FieldRule decimalRange(String field, double minExclusive, double maxInclusive) {
        return new FieldRule(
                field,
                false,
                v -> v.isNumber() && v.asDouble() > minExclusive && v.asDouble() <= maxInclusive,
                "取值须为 (" + minExclusive + ", " + maxInclusive + "]");
    }

    /** 布尔（enabled / isDefault / mode 开关类），可选字段。 */
    public static FieldRule bool(String field) {
        return new FieldRule(field, false, JsonNode::isBoolean, "须为布尔值");
    }

    /** 非空字符串（model / cron / fallback 等），可选字段。 */
    public static FieldRule nonBlank(String field) {
        return new FieldRule(field, false, v -> v.isTextual() && !v.asText().isBlank(), "不能为空");
    }

    /** 必填非空字符串。 */
    public static FieldRule requiredNonBlank(String field) {
        return new FieldRule(field, true, v -> v.isTextual() && !v.asText().isBlank(), "不能为空");
    }

    /** http(s) URL（provider baseUrl / 数据源端点），可选字段。 */
    public static FieldRule url(String field) {
        return new FieldRule(
                field,
                false,
                v ->
                        v.isTextual()
                                && (v.asText().startsWith("http://")
                                        || v.asText().startsWith("https://"))
                                && !v.asText().contains(" "),
                "须以 http(s):// 开头且不含空白");
    }

    /** 枚举（mode MOCK|REAL / scheduleType FIXED_DELAY|CRON 等），可选字段。 */
    public static FieldRule oneOf(String field, String... allowed) {
        List<String> options = List.of(allowed);
        return new FieldRule(
                field,
                false,
                v -> v.isTextual() && options.contains(v.asText()),
                "须为 " + String.join("|", options) + " 之一");
    }

    /** 子文档内每个值均为正整数的映射（如 cacheTtlSeconds{brief-type-N: 秒}），可选字段。 */
    public static FieldRule positiveLongMap(String field) {
        return new FieldRule(
                field,
                false,
                v -> {
                    if (!v.isObject()) {
                        return false;
                    }
                    for (JsonNode item : v) {
                        if (!item.isIntegralNumber() || item.asLong() <= 0) {
                            return false;
                        }
                    }
                    return true;
                },
                "须为对象且每个值均为正整数");
    }
}
