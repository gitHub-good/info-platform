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
 * 降级惯例）。已消费四键：{@code recommendation.global}（关联全局 + T133 feedBufferSeconds/scanWindowHours）、 {@code
 * recommendation.score}（recscore-v1 参数）、{@code recommendation.express}（快速通道预筛）、 {@code
 * recommendation.push}（T133 dailyLimit；mutedDays/escalated* 随 T134 反馈服务消费）。
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

    /** FEED 落库缓冲缺省秒（方案 §3.2：20s——防 L2 落库事务竞态）。 */
    static final int DEFAULT_FEED_BUFFER_SECONDS = 20;

    /** FEED 补跑窗缺省小时（方案 §3.2：24h——对齐管道补跑窗口，降级期积压恢复后照常处理）。 */
    static final int DEFAULT_SCAN_WINDOW_HOURS = 24;

    /** 日推送上限缺省（REQ：10 可配；SILENT 不占）。 */
    static final int DEFAULT_DAILY_LIMIT = 10;

    /** DISIKE 降频天数缺省（REQ 拍板六：7 天）。 */
    static final int DEFAULT_MUTED_DAYS = 7;

    /** 升级静默天数缺省（Should 条款：30 天）。 */
    static final int DEFAULT_ESCALATED_DAYS = 30;

    /** 升级阈值缺省（滚动窗内 DISLIKE 次数 ≥3 → 升级）。 */
    static final int DEFAULT_ESCALATE_THRESHOLD = 3;

    /** 升级滚动窗天数缺省（30 天）。 */
    static final int DEFAULT_ESCALATE_WINDOW_DAYS = 30;

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

    /** FEED 落库缓冲秒（feedBufferSeconds，T133 消费——消费扫描上界 now−buffer）。 */
    public int feedBufferSeconds() {
        int seconds = intOf(doc(KEY_GLOBAL), "feedBufferSeconds", DEFAULT_FEED_BUFFER_SECONDS);
        return seconds < 0 ? DEFAULT_FEED_BUFFER_SECONDS : seconds;
    }

    /** FEED 补跑窗小时（scanWindowHours，T133 消费——消费扫描下界 now−window）。 */
    public int scanWindowHours() {
        int hours = intOf(doc(KEY_GLOBAL), "scanWindowHours", DEFAULT_SCAN_WINDOW_HOURS);
        return hours <= 0 ? DEFAULT_SCAN_WINDOW_HOURS : hours;
    }

    /** 日推送上限（dailyLimit，T133 推送闸门消费——SILENT 态不占配额）。 */
    public int dailyLimit() {
        int limit = intOf(doc(KEY_PUSH), "dailyLimit", DEFAULT_DAILY_LIMIT);
        return limit < 0 ? DEFAULT_DAILY_LIMIT : limit;
    }

    /** DISLIKE 降频天数（mutedDays，T134 反馈闭环消费）。 */
    public int mutedDays() {
        int days = intOf(doc(KEY_PUSH), "mutedDays", DEFAULT_MUTED_DAYS);
        return days <= 0 ? DEFAULT_MUTED_DAYS : days;
    }

    /** 升级静默天数（escalatedDays——滚动窗 DISLIKE 达阈值后的 Should 条款升级）。 */
    public int escalatedDays() {
        int days = intOf(doc(KEY_PUSH), "escalatedDays", DEFAULT_ESCALATED_DAYS);
        return days <= 0 ? DEFAULT_ESCALATED_DAYS : days;
    }

    /** 升级阈值（escalateThreshold：滚动窗内 DISLIKE ≥ 该值 → 升级静默）。 */
    public int escalateThreshold() {
        int threshold = intOf(doc(KEY_PUSH), "escalateThreshold", DEFAULT_ESCALATE_THRESHOLD);
        return threshold <= 0 ? DEFAULT_ESCALATE_THRESHOLD : threshold;
    }

    /** 升级滚动窗天数（escalateWindowDays：DISLIKE 计数回看窗）。 */
    public int escalateWindowDays() {
        int days = intOf(doc(KEY_PUSH), "escalateWindowDays", DEFAULT_ESCALATE_WINDOW_DAYS);
        return days <= 0 ? DEFAULT_ESCALATE_WINDOW_DAYS : days;
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
