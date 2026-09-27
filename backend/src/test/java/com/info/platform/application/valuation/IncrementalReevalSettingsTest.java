package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.Importance;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * IncrementalReevalSettings 读取单测（M22 T190，方案 §3.5-1）：键缺失/字段非法字段级回退代码缺省（ValuationSettings
 * 同款降级惯例——增量链路不因配置损坏停摆）； 合法覆盖生效；minImportance 未知词回落 HIGH。
 */
class IncrementalReevalSettingsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IncrementalReevalSettings settingsOf(String json) {
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        Optional<RuntimeConfigEntry> entry =
                json == null
                        ? Optional.empty()
                        : Optional.of(
                                new RuntimeConfigEntry(
                                        "incremental.reeval", json, null, null, null));
        when(configService.read(anyString())).thenReturn(entry);
        return new IncrementalReevalSettings(configService, objectMapper);
    }

    @Test
    void keyMissing_fallsBackToCodeDefaults() {
        IncrementalReevalConfig config = settingsOf(null).current();

        assertThat(config.minScoreGap()).isEqualTo(0.5);
        assertThat(config.linkMinIntervalMinutes()).isEqualTo(10);
        assertThat(config.scanWindowHours()).isEqualTo(24);
        assertThat(config.eventBufferSeconds()).isEqualTo(20);
        assertThat(config.minImportance()).isEqualTo(Importance.HIGH);
    }

    @Test
    void corruptedJson_fallsBackToCodeDefaults() {
        IncrementalReevalConfig config = settingsOf("{not-json").current();

        assertThat(config.minScoreGap()).isEqualTo(0.5);
        assertThat(config.linkMinIntervalMinutes()).isEqualTo(10);
    }

    @Test
    void fieldOutOfRange_fallsBackFieldByField() {
        IncrementalReevalConfig config =
                settingsOf(
                                "{\"minScoreGap\":99,\"linkMinIntervalMinutes\":-1,"
                                        + "\"scanWindowHours\":0,\"eventBufferSeconds\":999,"
                                        + "\"minImportance\":\"WHATEVER\"}")
                        .current();

        assertThat(config.minScoreGap()).isEqualTo(0.5);
        assertThat(config.linkMinIntervalMinutes()).isEqualTo(10);
        assertThat(config.scanWindowHours()).isEqualTo(24);
        assertThat(config.eventBufferSeconds()).isEqualTo(20);
        assertThat(config.minImportance()).isEqualTo(Importance.HIGH);
    }

    @Test
    void validOverrides_takeEffect() {
        IncrementalReevalConfig config =
                settingsOf(
                                "{\"minScoreGap\":1.5,\"linkMinIntervalMinutes\":30,"
                                        + "\"scanWindowHours\":48,\"eventBufferSeconds\":0,"
                                        + "\"minImportance\":\"MEDIUM\"}")
                        .current();

        assertThat(config.minScoreGap()).isEqualTo(1.5);
        assertThat(config.linkMinIntervalMinutes()).isEqualTo(30);
        assertThat(config.scanWindowHours()).isEqualTo(48);
        assertThat(config.eventBufferSeconds()).isZero();
        assertThat(config.minImportance()).isEqualTo(Importance.MEDIUM);
    }
}
