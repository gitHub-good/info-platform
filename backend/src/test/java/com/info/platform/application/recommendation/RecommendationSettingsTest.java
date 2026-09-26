package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.recommendation.RecommendationScoreCalculator;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RecommendationSettings 单测（T131，方案 §4.9）：键缺失回落代码缺省、配置值热改读取（threshold/批大小/标的区上限/score 参数与 basis）。
 * AAA 结构。
 */
class RecommendationSettingsTest {

    private static final String SCORE_JSON =
            "{\"levelP1\":5.0,\"levelP2\":4.0,\"levelP3\":2.0,\"impHigh\":3.0,\"impMedium\":1.5,"
                    + "\"profileAlpha\":0.2,\"profileThemeHit\":0.5,\"profileHeatCap\":4.0,"
                    + "\"basis\":\"recscore-v2:custom\"}";

    private RecommendationSettings settingsOf(String key, String json) {
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        if (json != null) {
            RuntimeConfigEntry entry = mock(RuntimeConfigEntry.class);
            when(entry.json()).thenReturn(json);
            when(configService.read(key)).thenReturn(Optional.of(entry));
        } else {
            when(configService.read(anyString())).thenReturn(Optional.empty());
        }
        return new RecommendationSettings(configService, new ObjectMapper());
    }

    @Test
    void defaults_keyAbsent_fallsBackToCodeDefaults() {
        RecommendationSettings settings = settingsOf("recommendation.score", null);

        assertThat(settings.expressScoreThreshold()).isEqualTo(4.0);
        assertThat(settings.expressBatchSize()).isEqualTo(10);
        assertThat(settings.cardSubjectLimit()).isEqualTo(5);
        assertThat(settings.scoreParams().levelP1()).isEqualTo(3.0);
        assertThat(settings.scoreParams().basis())
                .isEqualTo(RecommendationScoreCalculator.ScoreParams.defaults().basis());
    }

    @Test
    void scoreParams_readsHotConfigAndBasis() {
        RecommendationSettings settings = settingsOf("recommendation.score", SCORE_JSON);

        assertThat(settings.scoreParams().levelP1()).isEqualTo(5.0);
        assertThat(settings.scoreParams().impMedium()).isEqualTo(1.5);
        assertThat(settings.scoreParams().profileAlpha()).isEqualTo(0.2);
        assertThat(settings.scoreParams().basis()).isEqualTo("recscore-v2:custom");
    }

    @Test
    void expressSettings_readsHotConfig() {
        String express = "{\"scoreThreshold\":5.5,\"batchSize\":6}";
        RecommendationSettings global =
                settingsOf("recommendation.global", "{\"cardSubjectLimit\":3}");
        RecommendationSettings expressSettings = settingsOf("recommendation.express", express);

        assertThat(expressSettings.expressScoreThreshold()).isEqualTo(5.5);
        assertThat(expressSettings.expressBatchSize()).isEqualTo(6);
        assertThat(global.cardSubjectLimit()).isEqualTo(3);
    }

    @Test
    void brokenValues_fallBackToDefaults() {
        // 非法值（负阈值/零批大小/非数字）回落缺省不阻断
        RecommendationSettings settings =
                settingsOf("recommendation.express", "{\"scoreThreshold\":-1,\"batchSize\":0}");

        assertThat(settings.expressScoreThreshold()).isEqualTo(4.0);
        assertThat(settings.expressBatchSize()).isEqualTo(10);
    }

    @Test
    void pushNoiseParams_defaultsAndHotConfig() {
        // Arrange：T134 反馈闭环消费的降噪四参数（键缺失 → 代码缺省 7/30/3/30）
        RecommendationSettings defaults = settingsOf("recommendation.push", null);
        RecommendationSettings hot =
                settingsOf(
                        "recommendation.push",
                        "{\"mutedDays\":3,\"escalatedDays\":15,\"escalateThreshold\":5,"
                                + "\"escalateWindowDays\":14}");

        // Act + Assert：缺省 + 热改（页面保存即对下一轮生效）
        assertThat(defaults.mutedDays()).isEqualTo(7);
        assertThat(defaults.escalatedDays()).isEqualTo(30);
        assertThat(defaults.escalateThreshold()).isEqualTo(3);
        assertThat(defaults.escalateWindowDays()).isEqualTo(30);
        assertThat(hot.mutedDays()).isEqualTo(3);
        assertThat(hot.escalatedDays()).isEqualTo(15);
        assertThat(hot.escalateThreshold()).isEqualTo(5);
        assertThat(hot.escalateWindowDays()).isEqualTo(14);
        assertThat(defaults.dailyLimit()).isEqualTo(10);
    }
}
