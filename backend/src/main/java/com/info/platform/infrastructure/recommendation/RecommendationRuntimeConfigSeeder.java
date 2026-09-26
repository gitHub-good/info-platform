package com.info.platform.infrastructure.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.common.RuntimeConfigSeeder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 推荐域运行时配置种子（{@code recommendation.*} 4 键，M16 T131，方案 §4.9 键表 / ADR-0032 seed-if-absent：DB 为权威，
 * 页面保存即热生效）。
 *
 * <p>已播种 4 键：{@code recommendation.global}（关联全局）/ {@code recommendation.score}（recscore-v1 参数，T131
 * 消费）/ {@code recommendation.express}（快速通道预筛，T131 消费）/ {@code recommendation.push}（推送闸门与降噪参数，T133
 * 消费方落地）。 缺省值与 {@code RecommendationSettings}/{@code RecommendationScoreCalculator} 代码缺省同源维护。
 */
@Component
public class RecommendationRuntimeConfigSeeder implements RuntimeConfigSeeder {

    private final ObjectMapper objectMapper;

    public RecommendationRuntimeConfigSeeder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed> seeds = new ArrayList<>();
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("feedBufferSeconds", 20);
        global.put("scanWindowHours", 24);
        global.put("cardSubjectLimit", 5);
        seeds.add(
                new RuntimeConfigSeed(
                        "recommendation.global",
                        write(global),
                        "推荐关联全局参数（FEED 扫描落库缓冲 20s/事件回看窗 24h/卡片标的区上限 5，M16 方案 §4.9）"));
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("levelP1", 3.0);
        score.put("levelP2", 2.0);
        score.put("levelP3", 1.0);
        score.put("impHigh", 2.0);
        score.put("impMedium", 1.0);
        score.put("profileAlpha", 0.25);
        score.put("profileThemeHit", 0.6);
        score.put("profileHeatCap", 5.0);
        score.put("basis", "recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)");
        seeds.add(
                new RuntimeConfigSeed(
                        "recommendation.score",
                        write(score),
                        "recscore-v1 综合分参数（层级 3/2/1 与重要度 2/1 为 REQ 拍板一冻结值；画像 α=0.25 上限加成 25%"
                                + " 只加权不越级；basis 版本串随系数调整升版，M16 方案 §4.4/§4.9）"));
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("dailyLimit", 10);
        push.put("mutedDays", 7);
        push.put("escalatedDays", 30);
        push.put("escalateThreshold", 3);
        push.put("escalateWindowDays", 30);
        seeds.add(
                new RuntimeConfigSeed(
                        "recommendation.push",
                        write(push),
                        "推荐推送闸门与降噪参数（日上限 10 可配/降频 7 天/滚动 30 天 3 次升级静默 30 天——T133 推送段消费，"
                                + "M16 方案 §4.6/§4.7/§4.9）"));
        Map<String, Object> express = new LinkedHashMap<>();
        express.put("scoreThreshold", 4.0);
        express.put("batchSize", 10);
        seeds.add(
                new RuntimeConfigSeed(
                        "recommendation.express",
                        write(express),
                        "快速通道预筛参数（ImportanceScorer 纯规则预筛分阈值 4.0 ≈ 源权重 2.0+一个强触发词 2.0；L1 批大小 10——"
                                + "首跑命中量 >30 条/日 连续两日提示提阈值，M16 方案 §4.3/§4.9）"));
        return seeds;
    }

    private String write(Map<String, Object> doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("推荐配置种子序列化失败: " + e.getOriginalMessage(), e);
        }
    }
}
