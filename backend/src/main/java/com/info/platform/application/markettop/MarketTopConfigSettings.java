package com.info.platform.application.markettop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 榜单配置读取（应用层，M21 方案 §4.7.3 + ADR-0017 快照热读）：每次消费现读 {@code market.top}，键缺失或字段非法字段级回退代码缺省
 * （ValuationSettings 同款降级惯例——回填预检不因配置损坏停摆）。写路径合法性由 {@code MarketTopConfigValidator}（30091）把关。
 */
@Service
public class MarketTopConfigSettings {

    /** 榜单配置键（与校验器/种子同源）。 */
    static final String CONFIG_KEY = "market.top";

    /** 参数可配区间（方案 §4.7.3：poolSize 100~800 / deepDiveLimit 30~50 / capRatio 0.05~1.0 / floor 0~1）。 */
    static final int POOL_SIZE_MIN = 100;

    static final int POOL_SIZE_MAX = 800;

    static final int DEEP_DIVE_LIMIT_MIN = 30;

    static final int DEEP_DIVE_LIMIT_MAX = 50;

    static final double CAP_RATIO_MIN = 0.05;

    static final double CAP_RATIO_MAX = 1.0;

    static final double ESTIMATE_MICROS_MIN = 1.0;

    static final double ESTIMATE_MICROS_MAX = 100_000_000.0;

    static final double COVERAGE_FLOOR_MIN = 0.0;

    static final double COVERAGE_FLOOR_MAX = 1.0;

    private static final Logger log = LoggerFactory.getLogger(MarketTopConfigSettings.class);

    private final RuntimeConfigService configService;

    private final ObjectMapper objectMapper;

    public MarketTopConfigSettings(RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 当前生效配置（热读；异常全兜底缺省——回填/粗筛不因配置损坏停摆）。 */
    public MarketTopConfig current() {
        MarketTopConfig defaults = MarketTopConfig.defaults();
        JsonNode doc = readDoc();
        MarketTopConfig config =
                new MarketTopConfig(
                        intOf(doc, "poolSize", POOL_SIZE_MIN, POOL_SIZE_MAX, defaults.poolSize()),
                        intOf(
                                doc,
                                "deepDiveLimit",
                                DEEP_DIVE_LIMIT_MIN,
                                DEEP_DIVE_LIMIT_MAX,
                                defaults.deepDiveLimit()),
                        doubleOf(
                                doc,
                                "deepDiveCostCapRatio",
                                CAP_RATIO_MIN,
                                CAP_RATIO_MAX,
                                defaults.deepDiveCostCapRatio()),
                        (long)
                                doubleOf(
                                        doc,
                                        "diveCostEstimateMicros",
                                        ESTIMATE_MICROS_MIN,
                                        ESTIMATE_MICROS_MAX,
                                        defaults.diveCostEstimateMicros()),
                        doubleOf(
                                doc,
                                "memberCoverageFloor",
                                COVERAGE_FLOOR_MIN,
                                COVERAGE_FLOOR_MAX,
                                defaults.memberCoverageFloor()));
        return config;
    }

    private JsonNode readDoc() {
        return configService.read(CONFIG_KEY).map(entry -> readTree(entry.json())).orElse(null);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("market.top 配置解析失败（回落代码缺省）: {}", e.getMessage());
            return null;
        }
    }

    private static double doubleOf(
            JsonNode doc, String field, double min, double max, double defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.isNumber()) {
            return defaultValue;
        }
        double value = node.asDouble();
        return value < min || value > max ? defaultValue : value;
    }

    private static int intOf(JsonNode doc, String field, int min, int max, int defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.canConvertToInt()) {
            return defaultValue;
        }
        int value = node.asInt();
        return value < min || value > max ? defaultValue : value;
    }
}
