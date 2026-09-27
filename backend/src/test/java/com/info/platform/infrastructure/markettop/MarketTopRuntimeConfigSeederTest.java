package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.markettop.MarketTopConfig;
import org.junit.jupiter.api.Test;

/**
 * MarketTopRuntimeConfigSeeder 单测（M21，ScoreWeightRuntimeConfigSeeder 同款）：种子 JSON 与
 * MarketTopConfig.defaults() 单一事实源对齐（5 字段逐项）。
 */
class MarketTopRuntimeConfigSeederTest {

    @Test
    void seedJson_matchesDefaultsSingleSourceOfTruth() throws Exception {
        String json = new MarketTopRuntimeConfigSeeder(new ObjectMapper()).seeds().get(0).json();

        JsonNode doc = new ObjectMapper().readTree(json);
        MarketTopConfig defaults = MarketTopConfig.defaults();

        assertThat(doc.get("poolSize").asInt()).isEqualTo(defaults.poolSize());
        assertThat(doc.get("deepDiveLimit").asInt()).isEqualTo(defaults.deepDiveLimit());
        assertThat(doc.get("deepDiveCostCapRatio").asDouble())
                .isEqualTo(defaults.deepDiveCostCapRatio());
        assertThat(doc.get("diveCostEstimateMicros").asLong())
                .isEqualTo(defaults.diveCostEstimateMicros());
        assertThat(doc.get("memberCoverageFloor").asDouble())
                .isEqualTo(defaults.memberCoverageFloor());
    }
}
