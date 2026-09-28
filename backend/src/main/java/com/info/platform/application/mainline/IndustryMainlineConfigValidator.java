package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.application.mainline.IndustryMainlineSettings.LeaderParams;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 主线/龙头配置校验器（M27 T243，方案 §4.3.3 保存侧防御）：两键字段级校验——权重三项和 = 1±0.001 / 子权重和 = 1±0.001 / topN ∈
 * [3,5]（mainline）与 [1,5]（leader）/ 窗口边界——非法抛 30096 INDUSTRY_MAINLINE_CONFIG_INVALID（msg 字段级，多问题 "; "
 * 连接），DB 原值保留继续生效。类型判定单一事实源在此（MarketTopConfigValidator 同因）：字符串数字不静默采信。
 */
@Component
public class IndustryMainlineConfigValidator implements RuntimeConfigValidator {

    @Override
    public boolean supports(String configKey) {
        return IndustryMainlineSettings.MAINLINE_KEY.equals(configKey)
                || IndustryMainlineSettings.LEADER_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems =
                IndustryMainlineSettings.MAINLINE_KEY.equals(configKey)
                        ? validateMainline(document)
                        : validateLeader(document);
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INDUSTRY_MAINLINE_CONFIG_INVALID, String.join("; ", problems));
        }
    }

    private List<String> validateMainline(JsonNode doc) {
        List<String> problems =
                new ArrayList<>(
                        IndustryMainlineSettings.missingFields(
                                doc, IndustryMainlineSettings.mainlineFields()));
        requireNumber(doc, "wp", 0d, 1d, problems);
        requireNumber(doc, "wh", 0d, 1d, problems);
        requireNumber(doc, "we", 0d, 1d, problems);
        requireNumber(doc, "priceWinDay", 0d, 1d, problems);
        requireNumber(doc, "priceWinD5", 0d, 1d, problems);
        requireNumber(doc, "heatH24", 0d, 1d, problems);
        requireNumber(doc, "heatD7", 0d, 1d, problems);
        requireNumber(doc, "heatDelta", 0d, 1d, problems);
        requireInt(doc, "topN", 3, 5, problems);
        requireInt(doc, "persistMinDays", 1, 5, problems);
        requireInt(doc, "persistWindowDays", 3, 10, problems);
        requireInt(doc, "topThirdRank", 5, 15, problems);
        requireInt(doc, "divergenceHeatRank", 5, 20, problems);
        requireSum(doc, List.of("wp", "wh", "we"), problems);
        requireSum(doc, List.of("priceWinDay", "priceWinD5"), problems);
        requireSum(doc, List.of("heatH24", "heatD7", "heatDelta"), problems);
        // 门槛语义守卫：最小持续天数不得高于窗口天数（≥n/m 恒不可达即配置死局）
        JsonNode minDays = doc.path("persistMinDays");
        JsonNode windowDays = doc.path("persistWindowDays");
        if (minDays.isIntegralNumber()
                && windowDays.isIntegralNumber()
                && minDays.asInt() > windowDays.asInt()) {
            problems.add("persistMinDays: 不得大于 persistWindowDays（门槛恒不可达）");
        }
        return problems;
    }

    private List<String> validateLeader(JsonNode doc) {
        List<String> problems =
                new ArrayList<>(
                        IndustryMainlineSettings.missingFields(
                                doc, IndustryMainlineSettings.leaderFields()));
        requireNumber(doc, "wa", 0d, 1d, problems);
        requireNumber(doc, "wv", 0d, 1d, problems);
        requireNumber(doc, "wq", 0d, 1d, problems);
        requireNumber(doc, "qDay", 0d, 1d, problems);
        requireNumber(doc, "qD5", 0d, 1d, problems);
        requireInt(doc, "mentionDays", 3, 30, problems);
        requireInt(doc, "topN", 1, 5, problems);
        requireSum(doc, List.of("wa", "wv", "wq"), problems);
        requireSum(doc, List.of("qDay", "qD5"), problems);
        return problems;
    }

    private static void requireNumber(
            JsonNode doc, String field, double min, double max, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            return; // 必填缺口已由 missingFields 汇总
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

    /** 权重和校验：|Σw − 1| ≤ 0.001（字段齐整数值合法时才判和——缺口/非法字段已单独报）。 */
    private static void requireSum(JsonNode doc, List<String> fields, List<String> problems) {
        boolean allPresent = fields.stream().allMatch(field -> doc.path(field).isNumber());
        if (!allPresent) {
            return;
        }
        double sum = fields.stream().mapToDouble(field -> doc.path(field).asDouble()).sum();
        if (Math.abs(sum - 1d) > IndustryMainlineSettings.WEIGHT_SUM_TOLERANCE) {
            problems.add(String.join("+", fields) + ": 权重和须为 1±0.001（当前 " + sum + "）");
        }
    }

    /** 校验后参数快照（服务写路径二次断言面，非必须）。 */
    static Params toMainlineParams(JsonNode doc) {
        return IndustryMainlineSettings.parseMainline(doc);
    }

    static LeaderParams toLeaderParams(JsonNode doc) {
        return IndustryMainlineSettings.parseLeader(doc);
    }
}
