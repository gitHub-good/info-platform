package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.valuation.ValuationParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 评分参数读取（应用层，M20 方案 §4.3 + ADR-0017 快照热读）：每轮 FACTOR_SNAPSHOT 现读 {@code score.weight} （保存 → 快照替换 →
 * 下一轮重算按新参数与 basis 生效）。键缺失或字段非法字段级回退代码缺省（不阻断轮次—— PipelineSettings 同款降级惯例）；全零权重整体回落缺省（除零防线）。写路径合法性由
 * T172 校验器（30087）把关。
 */
@Service
public class ValuationSettings {

    static final String KEY_SCORE_WEIGHT = "score.weight";

    /** 参数可配区间（方案 §4.3：权重 [0,1] / 窗 5~30|10~60 / hl 1~15 / K 0.5~10 / 阈值 0~100）。 */
    static final int CATALYST_WINDOW_MIN = 5;

    static final int CATALYST_WINDOW_MAX = 30;

    static final int ASSOC_WINDOW_MIN = 10;

    static final int ASSOC_WINDOW_MAX = 60;

    static final double HALF_LIFE_MIN = 1.0;

    static final double HALF_LIFE_MAX = 15.0;

    static final double K_MIN = 0.5;

    static final double K_MAX = 10.0;

    private static final Logger log = LoggerFactory.getLogger(ValuationSettings.class);

    private final RuntimeConfigService configService;

    private final ObjectMapper objectMapper;

    public ValuationSettings(RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 当前生效参数（热读；异常全兜底缺省——引擎不因配置损坏停摆）。 */
    public ValuationParams params() {
        ValuationParams defaults = ValuationParams.defaults();
        JsonNode doc = readDoc();
        ValuationParams params =
                defaults.withWCatalyst(weightOf(doc, "wCatalyst", defaults.wCatalyst()))
                        .withWConduction(weightOf(doc, "wConduction", defaults.wConduction()))
                        .withWFundamental(weightOf(doc, "wFundamental", defaults.wFundamental()))
                        .withWRisk(weightOf(doc, "wRisk", defaults.wRisk()))
                        .withWValuation(weightOf(doc, "wValuation", defaults.wValuation()))
                        .withCatalystWindowDays(
                                intOf(
                                        doc,
                                        "catalystWindowDays",
                                        CATALYST_WINDOW_MIN,
                                        CATALYST_WINDOW_MAX,
                                        defaults.catalystWindowDays()))
                        .withAssocWindowDays(
                                intOf(
                                        doc,
                                        "assocWindowDays",
                                        ASSOC_WINDOW_MIN,
                                        ASSOC_WINDOW_MAX,
                                        defaults.assocWindowDays()))
                        .withHalfLifeDays(
                                doubleOf(
                                        doc,
                                        "halfLifeDays",
                                        HALF_LIFE_MIN,
                                        HALF_LIFE_MAX,
                                        defaults.halfLifeDays()))
                        .withK1Saturation(
                                doubleOf(
                                        doc, "k1Saturation", K_MIN, K_MAX, defaults.k1Saturation()))
                        .withK3Saturation(
                                doubleOf(
                                        doc, "k3Saturation", K_MIN, K_MAX, defaults.k3Saturation()))
                        .withBtCatalystMin(
                                intOf(doc, "btCatalystMin", 0, 100, defaults.btCatalystMin()))
                        .withBtConductionMin(
                                intOf(doc, "btConductionMin", 0, 100, defaults.btConductionMin()))
                        .withBtRiskMin(intOf(doc, "btRiskMin", 0, 100, defaults.btRiskMin()));
        if (params.weightSum() <= 0.0) {
            log.warn("score.weight 权重和 ≤0（basis={}），整体回落代码缺省", params.basis());
            return defaults;
        }
        return params;
    }

    private JsonNode readDoc() {
        JsonNode doc =
                configService
                        .read(KEY_SCORE_WEIGHT)
                        .map(entry -> readTree(entry.json()))
                        .orElse(null);
        return doc;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("score.weight 配置解析失败（回落代码缺省）: {}", e.getMessage());
            return null;
        }
    }

    private static double weightOf(JsonNode doc, String field, double defaultValue) {
        return doubleOf(doc, field, 0.0, 1.0, defaultValue);
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
