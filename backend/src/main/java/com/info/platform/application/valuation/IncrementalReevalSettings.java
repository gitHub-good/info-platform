package com.info.platform.application.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.Importance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 增量重评配置读取（应用层，M22 方案 §3.5-1 + ADR-0017 快照热读）：每轮 tick 现读 {@code incremental.reeval}，键缺失或字段非法
 * 字段级回退代码缺省（ValuationSettings 同款降级惯例——增量链路不因配置损坏停摆）。写路径合法性由 {@code
 * IncrementalReevalConfigValidator}（30092）把关。
 */
@Service
public class IncrementalReevalSettings {

    /** 增量重评配置键（与校验器/种子同源）。 */
    static final String CONFIG_KEY = "incremental.reeval";

    /** 参数可配区间（方案 §3.5-1：gap 0~10 / 联动间隔 0~60min / 扫描窗 1~72h / 缓冲 0~120s）。 */
    static final double MIN_SCORE_GAP_MIN = 0.0;

    static final double MIN_SCORE_GAP_MAX = 10.0;

    static final int LINK_INTERVAL_MIN = 0;

    static final int LINK_INTERVAL_MAX = 60;

    static final int SCAN_WINDOW_MIN = 1;

    static final int SCAN_WINDOW_MAX = 72;

    static final int BUFFER_SECONDS_MIN = 0;

    static final int BUFFER_SECONDS_MAX = 120;

    private static final Logger log = LoggerFactory.getLogger(IncrementalReevalSettings.class);

    private final RuntimeConfigService configService;

    private final ObjectMapper objectMapper;

    public IncrementalReevalSettings(
            RuntimeConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 当前生效配置（热读；异常全兜底缺省）。 */
    public IncrementalReevalConfig current() {
        IncrementalReevalConfig defaults = IncrementalReevalConfig.defaults();
        JsonNode doc = readDoc();
        return new IncrementalReevalConfig(
                doubleOf(
                        doc,
                        "minScoreGap",
                        MIN_SCORE_GAP_MIN,
                        MIN_SCORE_GAP_MAX,
                        defaults.minScoreGap()),
                intOf(
                        doc,
                        "linkMinIntervalMinutes",
                        LINK_INTERVAL_MIN,
                        LINK_INTERVAL_MAX,
                        defaults.linkMinIntervalMinutes()),
                intOf(
                        doc,
                        "scanWindowHours",
                        SCAN_WINDOW_MIN,
                        SCAN_WINDOW_MAX,
                        defaults.scanWindowHours()),
                intOf(
                        doc,
                        "eventBufferSeconds",
                        BUFFER_SECONDS_MIN,
                        BUFFER_SECONDS_MAX,
                        defaults.eventBufferSeconds()),
                importanceOf(doc, "minImportance", defaults.minImportance()));
    }

    private JsonNode readDoc() {
        return configService.read(CONFIG_KEY).map(entry -> readTree(entry.json())).orElse(null);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("incremental.reeval 配置解析失败（回落代码缺省）: {}", e.getMessage());
            return null;
        }
    }

    private static Importance importanceOf(JsonNode doc, String field, Importance defaultValue) {
        JsonNode node = doc == null ? null : doc.get(field);
        if (node == null || !node.isTextual()) {
            return defaultValue;
        }
        Importance importance = Importance.fromName(node.asText());
        return importance == null ? defaultValue : importance;
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
