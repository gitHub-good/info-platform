package com.info.platform.application.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.recommendation.RecommendationScoreCalculator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 推荐域运行参数（应用层，M16 T131，方案 §4.9 {@code recommendation.*} 配置键的消费点）。
 *
 * <p>每次消费现读（配置中心快照，页面保存即对下一轮生效）；键缺失或字段损坏回落代码缺省并记 WARN（不阻断链路——沿 {@code PipelineSettings}
 * 降级惯例）。已消费三键：{@code recommendation.global}（关联全局）、{@code recommendation.score}（recscore-v1
 * 参数）、{@code recommendation.express}（快速通道预筛）； {@code recommendation.push}（日上限/降噪参数）随 T133 消费方落地。
 */
@Service
public class RecommendationSettings {

    private static final Logger log = LoggerFactory.getLogger(RecommendationSettings.class);

    /** express 预筛分阈值缺省（方案 §3.1 裁决 1：≈ 源权重 2.0 + 一个强触发词 2.0）。 */
    static final double DEFAULT_EXPRESS_SCORE_THRESHOLD = 4.0;

    /** express 单 tick 候选扫描上限（防御性；强触发条目日 ~10~30 条）。 */
    public static final int EXPRESS_SCAN_CAP = 100;

    /** express L1 批大小缺省（方案 §4.3：批 ≤10——与 L2 批大小对齐）。 */
    static final int DEFAULT_EXPRESS_BATCH_SIZE = 10;

    /** 卡片标的区上限缺省（方案 §4.1：subjects ≤5）。 */
    static final int DEFAULT_CARD_SUBJECT_LIMIT = 5;

    static final String KEY_GLOBAL = "recommendation.global";

    static final String KEY_SCORE = "recommendation.score";

    static final String KEY_EXPRESS = "recommendation.express";

    static final String KEY_PUSH = "recommendation.push";

    private final RuntimeConfigService configService;

    private final ObjectMapper objectMapper;

    public RecommendationSettings(RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 卡片标的区上限（cardSubjectLimit）。 */
    public int cardSubjectLimit() {
        int limit = intOf(doc(KEY_GLOBAL), "cardSubjectLimit", DEFAULT_CARD_SUBJECT_LIMIT);
        return limit <= 0 ? DEFAULT_CARD_SUBJECT_LIMIT : limit;
    }

    /** recscore-v1 参数组装（RecommendationScoreCalculator 消费形态；basis 版本串随参数配置）。 */
    public RecommendationScoreCalculator.ScoreParams scoreParams() {
        JsonNode doc = doc(KEY_SCORE);
        RecommendationScoreCalculator.ScoreParams defaults =
                RecommendationScoreCalculator.ScoreParams.defaults();
        String basis = textOf(doc, "basis", defaults.basis());
        return new RecommendationScoreCalculator.ScoreParams(
                positiveDoubleOf(doc, "levelP1", defaults.levelP1()),
                positiveDoubleOf(doc, "levelP2", defaults.levelP2()),
                positiveDoubleOf(doc, "levelP3", defaults.levelP3()),
                positiveDoubleOf(doc, "impHigh", defaults.impHigh()),
                positiveDoubleOf(doc, "impMedium", defaults.impMedium()),
                nonNegativeDoubleOf(doc, "profileAlpha", defaults.profileAlpha()),
                nonNegativeDoubleOf(doc, "profileThemeHit", defaults.profileThemeHit()),
                positiveDoubleOf(doc, "profileHeatCap", defaults.profileHeatCap()),
                basis);
    }

    /** express 预筛分阈值（scoreThreshold，热改键——首跑校准入口）。 */
    public double expressScoreThreshold() {
        return nonNegativeDoubleOf(
                doc(KEY_EXPRESS), "scoreThreshold", DEFAULT_EXPRESS_SCORE_THRESHOLD);
    }

    /** express L1 批大小（batchSize）。 */
    public int expressBatchSize() {
        int size = intOf(doc(KEY_EXPRESS), "batchSize", DEFAULT_EXPRESS_BATCH_SIZE);
        return size <= 0 ? DEFAULT_EXPRESS_BATCH_SIZE : size;
    }

    private JsonNode doc(String configKey) {
        return configService.read(configKey).map(entry -> readTree(entry.json())).orElse(null);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("推荐配置解析失败（回落代码缺省）: {}", e.getMessage());
            return null;
        }
    }

    private static JsonNode field(JsonNode doc, String field) {
        return doc == null ? null : doc.get(field);
    }

    private static int intOf(JsonNode doc, String field, int defaultValue) {
        JsonNode node = field(doc, field);
        return node == null || !node.canConvertToInt() ? defaultValue : node.asInt();
    }

    private static double doubleOf(JsonNode doc, String field, double defaultValue) {
        JsonNode node = field(doc, field);
        return node == null || !node.isNumber() ? defaultValue : node.asDouble();
    }

    private static double positiveDoubleOf(JsonNode doc, String field, double defaultValue) {
        double value = doubleOf(doc, field, defaultValue);
        return value <= 0 ? defaultValue : value;
    }

    private static double nonNegativeDoubleOf(JsonNode doc, String field, double defaultValue) {
        double value = doubleOf(doc, field, defaultValue);
        return value < 0 ? defaultValue : value;
    }

    private static String textOf(JsonNode doc, String field, String defaultValue) {
        JsonNode node = field(doc, field);
        return node != null && node.isTextual() && !node.asText().isBlank()
                ? node.asText()
                : defaultValue;
    }

    /** 便捷读取：任一键当前 JSON（Seeder 种子断言与排障用）。 */
    public Optional<String> rawDoc(String configKey) {
        return configService.read(configKey).map(entry -> entry.json());
    }
}
