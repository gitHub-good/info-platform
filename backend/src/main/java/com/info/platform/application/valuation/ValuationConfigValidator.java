package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 评分权重键校验器（M20 T172，方案 §4.3 {@code score.weight} 保存侧防御）：五维权重 ∈ [0,1] 且权和 ∈ (0, 1.05] （合成自动归一，1.05
 * 为归一容差上限）、事件窗 5~30 / 关联窗 10~60 天、半衰期 1~15、双饱和常数 0.5~10、「有突破」三阈值 0~100 整数——非法抛 30087
 * VALUATION_CONFIG_INVALID（msg 字段级，多问题 "; " 连接），DB 原值保留继续生效。
 *
 * <p>类型判定单一事实源在此（RetentionConfigValidator D3 同因）：字符串数字/浮点阈值不静默采信。读侧第二道防御见 {@code
 * ValuationSettings}（字段级回退缺省）；basis 为代码派生字段不校验（未知字段不拒绝）。
 */
@Component
public class ValuationConfigValidator implements RuntimeConfigValidator {

    /** 权重配置键（与 ValuationSettings 读取键同源）。 */
    public static final String CONFIG_KEY = "score.weight";

    /** 权重上界（[0,1]）。 */
    static final double WEIGHT_MAX = 1.0;

    /** 权和归一容差上界（方案 §6 负向清单「Σ&gt;1.05」拦截；合成按 Σ 归一无须恰为 1，但远离 1 的配置视为误配）。 */
    static final double WEIGHT_SUM_TOLERANCE = 1.05;

    private static final String WEIGHT_FIELDS = "五维权重";

    @Override
    public boolean supports(String configKey) {
        return CONFIG_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        double wCatalyst = requireDecimal(document, "wCatalyst", 0.0, WEIGHT_MAX, problems);
        double wConduction = requireDecimal(document, "wConduction", 0.0, WEIGHT_MAX, problems);
        double wFundamental = requireDecimal(document, "wFundamental", 0.0, WEIGHT_MAX, problems);
        double wRisk = requireDecimal(document, "wRisk", 0.0, WEIGHT_MAX, problems);
        double wValuation = requireDecimal(document, "wValuation", 0.0, WEIGHT_MAX, problems);
        double weightSum = wCatalyst + wConduction + wFundamental + wRisk + wValuation;
        if (allWeightsPresent(document) && (weightSum <= 0.0 || weightSum > WEIGHT_SUM_TOLERANCE)) {
            problems.add(
                    WEIGHT_FIELDS
                            + ": 权重和须 > 0 且 ≤ "
                            + WEIGHT_SUM_TOLERANCE
                            + "（当前 "
                            + String.format(java.util.Locale.ROOT, "%.2f", weightSum)
                            + "，合成自动归一）");
        }
        requireInt(document, "catalystWindowDays", 5, 30, problems);
        requireInt(document, "assocWindowDays", 10, 60, problems);
        requireDecimal(document, "halfLifeDays", 1.0, 15.0, problems);
        requireDecimal(document, "k1Saturation", 0.5, 10.0, problems);
        requireDecimal(document, "k3Saturation", 0.5, 10.0, problems);
        requireInt(document, "btCatalystMin", 0, 100, problems);
        requireInt(document, "btConductionMin", 0, 100, problems);
        requireInt(document, "btRiskMin", 0, 100, problems);
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALUATION_CONFIG_INVALID, String.join("; ", problems));
        }
    }

    /** 五权重是否全部在场（权和跨字段校验只对完整载荷负责，缺失已由字段级「必填」报告）。 */
    private static boolean allWeightsPresent(JsonNode doc) {
        return fieldPresent(doc, "wCatalyst")
                && fieldPresent(doc, "wConduction")
                && fieldPresent(doc, "wFundamental")
                && fieldPresent(doc, "wRisk")
                && fieldPresent(doc, "wValuation");
    }

    private static boolean fieldPresent(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        return node != null && !node.isNull();
    }

    /**
     * @return 合法数值（非法/缺失返回 0 供跨字段求和跳过）。
     */
    private static double requireDecimal(
            JsonNode doc, String field, double min, double max, List<String> problems) {
        JsonNode node = doc.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + ": 必填");
            return 0;
        }
        if (!node.isNumber()) {
            problems.add(field + ": 须为数值");
            return 0;
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            problems.add(field + ": 须在 " + min + " ~ " + max + " 范围内");
            return 0;
        }
        return value;
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
