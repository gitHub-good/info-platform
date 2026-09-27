package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigValidator;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 榜单配置键校验器（M21 T181，方案 §4.7.3 {@code market.top} 保存侧防御）：poolSize 100~800 整数 / deepDiveLimit 30~50
 * 整数（蓝图区间硬校验——「全量 LLM 逐股永不发生」的配置面防线，§4.3.4 前置拦截）/ deepDiveCostCapRatio 0.05~1.0 /
 * diveCostEstimateMicros 正整数 / memberCoverageFloor 0~1——非法抛 30091 MARKET_TOP_CONFIG_INVALID（msg
 * 字段级，多问题 "; " 连接），DB 原值保留继续生效。
 *
 * <p>类型判定单一事实源在此（ValuationConfigValidator 同因）：字符串数字不静默采信。读侧第二道防御见 {@link
 * MarketTopConfigSettings}（字段级回退缺省）。
 */
@Component
public class MarketTopConfigValidator implements RuntimeConfigValidator {

    /** 榜单配置键（与 MarketTopConfigSettings 读取键同源）。 */
    public static final String CONFIG_KEY = MarketTopConfigSettings.CONFIG_KEY;

    @Override
    public boolean supports(String configKey) {
        return CONFIG_KEY.equals(configKey);
    }

    @Override
    public void validate(String configKey, JsonNode document) {
        List<String> problems = new ArrayList<>();
        requireInt(
                document,
                "poolSize",
                MarketTopConfigSettings.POOL_SIZE_MIN,
                MarketTopConfigSettings.POOL_SIZE_MAX,
                problems);
        requireInt(
                document,
                "deepDiveLimit",
                MarketTopConfigSettings.DEEP_DIVE_LIMIT_MIN,
                MarketTopConfigSettings.DEEP_DIVE_LIMIT_MAX,
                problems);
        requireDecimal(
                document,
                "deepDiveCostCapRatio",
                MarketTopConfigSettings.CAP_RATIO_MIN,
                MarketTopConfigSettings.CAP_RATIO_MAX,
                problems);
        requireDecimal(
                document,
                "diveCostEstimateMicros",
                MarketTopConfigSettings.ESTIMATE_MICROS_MIN,
                MarketTopConfigSettings.ESTIMATE_MICROS_MAX,
                problems);
        requireDecimal(
                document,
                "memberCoverageFloor",
                MarketTopConfigSettings.COVERAGE_FLOOR_MIN,
                MarketTopConfigSettings.COVERAGE_FLOOR_MAX,
                problems);
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.MARKET_TOP_CONFIG_INVALID, String.join("; ", problems));
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
