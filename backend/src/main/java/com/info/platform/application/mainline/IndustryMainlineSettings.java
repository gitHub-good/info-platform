package com.info.platform.application.mainline;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.mainline.MainlineCalculator.Params;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 主线/龙头配置读取侧类型化视图（M27 T243，方案 §4.3.3 + ADR-0063 裁决 4/5）：{@code industry.mainline} / {@code
 * industry.leader} 两键（页面 GET/PATCH 热生效，沿 score.weight 先例）。字段级回退防御（ValuationConfigSettings 同款）：
 * 键缺失/字段非法 → 回退代码缺省——计算永不因配置损坏而炸。
 */
@Component
public class IndustryMainlineSettings {

    /** 主线配置键。 */
    public static final String MAINLINE_KEY = "industry.mainline";

    /** 龙头配置键。 */
    public static final String LEADER_KEY = "industry.leader";

    /** 权重三项和容差（|Σw − 1| ≤ 0.001，校验器同款）。 */
    static final double WEIGHT_SUM_TOLERANCE = 0.001;

    private final RuntimeConfigService configService;

    public IndustryMainlineSettings(RuntimeConfigService configService) {
        this.configService = configService;
    }

    /** 当前主线参数（读侧回退：键缺失 = 代码缺省 §4.3.3 冻结值）。 */
    public Params mainlineParams() {
        return parseMainline(document(MAINLINE_KEY));
    }

    /** 当前龙头参数（读侧回退同上）。 */
    public LeaderParams leaderParams() {
        return parseLeader(document(LEADER_KEY));
    }

    private JsonNode document(String key) {
        return configService.read(key).map(entry -> entry.document()).orElse(null);
    }

    /** 静态解析（单测/种子共用；doc null → 全缺省）。 */
    public static Params parseMainline(JsonNode doc) {
        return new Params(
                doubleOf(doc, "wp", 0.40),
                doubleOf(doc, "wh", 0.35),
                doubleOf(doc, "we", 0.25),
                doubleOf(doc, "priceWinDay", 0.5),
                doubleOf(doc, "priceWinD5", 0.5),
                doubleOf(doc, "heatH24", 0.5),
                doubleOf(doc, "heatD7", 0.3),
                doubleOf(doc, "heatDelta", 0.2),
                intOf(doc, "topN", 5),
                intOf(doc, "persistMinDays", 2),
                intOf(doc, "persistWindowDays", 5),
                intOf(doc, "topThirdRank", 10),
                intOf(doc, "divergenceHeatRank", 13));
    }

    /** 龙头参数（§3.5 冻结缺省：wa 0.50 / wv 0.35 / wq 0.15 / mentionDays 7 / topN 3 / qDay+qD5 0.5+0.5）。 */
    public static LeaderParams parseLeader(JsonNode doc) {
        return new LeaderParams(
                doubleOf(doc, "wa", 0.50),
                doubleOf(doc, "wv", 0.35),
                doubleOf(doc, "wq", 0.15),
                intOf(doc, "mentionDays", 7),
                intOf(doc, "topN", 3),
                doubleOf(doc, "qDay", 0.5),
                doubleOf(doc, "qD5", 0.5));
    }

    /** 龙头三维权重与窗口（{@code industry.leader} 键）。 */
    public record LeaderParams(
            double wa, double wv, double wq, int mentionDays, int topN, double qDay, double qD5) {}

    private static double doubleOf(JsonNode doc, String field, double fallback) {
        if (doc == null) {
            return fallback;
        }
        JsonNode node = doc.get(field);
        return node != null && node.isNumber() ? node.asDouble() : fallback;
    }

    private static int intOf(JsonNode doc, String field, int fallback) {
        if (doc == null) {
            return fallback;
        }
        JsonNode node = doc.get(field);
        return node != null && node.isIntegralNumber() ? node.asInt() : fallback;
    }

    /** 两键全字段名清单（校验器必填断言共用单一事实源）。 */
    static List<String> mainlineFields() {
        return List.of(
                "wp",
                "wh",
                "we",
                "priceWinDay",
                "priceWinD5",
                "heatH24",
                "heatD7",
                "heatDelta",
                "topN",
                "persistMinDays",
                "persistWindowDays",
                "topThirdRank",
                "divergenceHeatRank");
    }

    static List<String> leaderFields() {
        return List.of("wa", "wv", "wq", "mentionDays", "topN", "qDay", "qD5");
    }

    /** 必填字段缺口（校验器/门面共用；doc null = 全缺）。 */
    static List<String> missingFields(JsonNode doc, List<String> fields) {
        List<String> missing = new ArrayList<>();
        for (String field : fields) {
            JsonNode node = doc == null ? null : doc.get(field);
            if (node == null || node.isNull()) {
                missing.add(field + ": 必填");
            }
        }
        return missing;
    }
}
