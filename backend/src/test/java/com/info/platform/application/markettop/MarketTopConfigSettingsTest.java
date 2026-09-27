package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * MarketTopConfigSettings 单测（M21，方案 §4.7.3 读侧第二道防御）：键缺失全默认 / 合法文档逐字段采信 / 越界与类型非法字段级回退
 * （ValuationSettings 同款口径——回填预检不因配置损坏停摆）。
 */
class MarketTopConfigSettingsTest {

    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketTopConfigSettings settings =
            new MarketTopConfigSettings(configService, mapper);

    private void stubConfig(String json) {
        com.fasterxml.jackson.databind.JsonNode document;
        try {
            document = mapper.readTree(json);
        } catch (Exception e) {
            document = null; // 损坏文档——readDoc 侧 json 解析回退路径由 corruptedJson_allDefaults 覆盖
        }
        when(configService.read(anyString()))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        "market.top",
                                        json,
                                        document,
                                        null,
                                        Instant.parse("2026-09-22T01:00:00Z"))));
    }

    @Test
    void missingKey_allDefaults() {
        when(configService.read(anyString())).thenReturn(Optional.empty());

        MarketTopConfig config = settings.current();

        MarketTopConfig defaults = MarketTopConfig.defaults();
        assertThat(config).isEqualTo(defaults);
        assertThat(config.poolSize()).isEqualTo(300);
        assertThat(config.deepDiveLimit()).isEqualTo(40);
        assertThat(config.memberCoverageFloor()).isEqualTo(0.80);
    }

    @Test
    void validDocument_fieldsAdopted() {
        stubConfig(
                "{\"poolSize\":500,\"deepDiveLimit\":50,\"deepDiveCostCapRatio\":0.5,"
                        + "\"diveCostEstimateMicros\":200000,\"memberCoverageFloor\":0.9}");

        MarketTopConfig config = settings.current();

        assertThat(config.poolSize()).isEqualTo(500);
        assertThat(config.deepDiveLimit()).isEqualTo(50);
        assertThat(config.deepDiveCostCapRatio()).isEqualTo(0.5);
        assertThat(config.diveCostEstimateMicros()).isEqualTo(200_000L);
        assertThat(config.memberCoverageFloor()).isEqualTo(0.9);
    }

    @Test
    void outOfRangeOrBadTypeFields_fieldLevelFallback() {
        // poolSize 越界 / deepDiveLimit 字符串 / capRatio 越界 / floor 缺失——各自回退缺省，好字段不受牵连
        stubConfig(
                "{\"poolSize\":9999,\"deepDiveLimit\":\"40\",\"deepDiveCostCapRatio\":2.0,"
                        + "\"diveCostEstimateMicros\":50000}");

        MarketTopConfig config = settings.current();
        MarketTopConfig defaults = MarketTopConfig.defaults();

        assertThat(config.poolSize()).isEqualTo(defaults.poolSize());
        assertThat(config.deepDiveLimit()).isEqualTo(defaults.deepDiveLimit());
        assertThat(config.deepDiveCostCapRatio()).isEqualTo(defaults.deepDiveCostCapRatio());
        assertThat(config.diveCostEstimateMicros()).isEqualTo(50_000L); // 合法字段采信
        assertThat(config.memberCoverageFloor()).isEqualTo(defaults.memberCoverageFloor());
    }

    @Test
    void corruptedJson_allDefaults() {
        stubConfig("not-a-json");

        assertThat(settings.current()).isEqualTo(MarketTopConfig.defaults());
    }
}
