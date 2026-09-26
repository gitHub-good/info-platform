package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RecommendationRuntimeConfigSeeder 种子单测（T131，M16 方案 §4.9）：4 键齐全、recscore-v1 冻结系数与 basis 串、express
 * 阈值 4.0/批 10、推送闸门参数（T133 消费先播种防抢注）。
 */
class RecommendationRuntimeConfigSeederTest {

    private final RecommendationRuntimeConfigSeeder seeder =
            new RecommendationRuntimeConfigSeeder(new ObjectMapper());

    private RuntimeConfigSeed seedOf(String key) {
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺种子: " + key));
    }

    @Test
    void seeds_carriesAllFourRecommendationKeys() {
        List<String> keys = seeder.seeds().stream().map(RuntimeConfigSeed::configKey).toList();
        assertThat(keys)
                .containsExactly(
                        "recommendation.global", "recommendation.score",
                        "recommendation.push", "recommendation.express");
    }

    @Test
    void seeds_score_freezesRecscoreV1CoefficientsAndBasis() {
        // REQ 拍板一冻结值 + 画像 α=0.25/theme=0.6/heatCap=5.0 + basis 版本串（ADR-0045 先例）
        assertThat(seedOf("recommendation.score").json())
                .contains("\"levelP1\":3.0")
                .contains("\"levelP2\":2.0")
                .contains("\"levelP3\":1.0")
                .contains("\"impHigh\":2.0")
                .contains("\"impMedium\":1.0")
                .contains("\"profileAlpha\":0.25")
                .contains("\"profileThemeHit\":0.6")
                .contains("\"profileHeatCap\":5.0")
                .contains(
                        "\"basis\":\"recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)\"");
    }

    @Test
    void seeds_express_scoreThreshold4AndBatch10() {
        assertThat(seedOf("recommendation.express").json())
                .contains("\"scoreThreshold\":4.0")
                .contains("\"batchSize\":10");
    }

    @Test
    void seeds_push_dailyLimit10AndMuteWindows() {
        assertThat(seedOf("recommendation.push").json())
                .contains("\"dailyLimit\":10")
                .contains("\"mutedDays\":7")
                .contains("\"escalatedDays\":30")
                .contains("\"escalateThreshold\":3")
                .contains("\"escalateWindowDays\":30");
    }
}
