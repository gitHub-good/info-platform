package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * IncrementalReevalRuntimeConfigSeeder 单测（M22 T190，方案 §3.5-1）：incremental.reeval 种子与
 * Settings/Validator 键同源、缺省值单一事实源 （MarketTopRuntimeConfigSeeder 同款）。
 */
class IncrementalReevalRuntimeConfigSeederTest {

    private final IncrementalReevalRuntimeConfigSeeder seeder =
            new IncrementalReevalRuntimeConfigSeeder(new ObjectMapper());

    @Test
    void seedsIncrementalReevalKeyWithDefaults() {
        List<RuntimeConfigSeed> seeds = seeder.seeds();

        assertThat(seeds).hasSize(1);
        RuntimeConfigSeed seed = seeds.get(0);
        assertThat(seed.configKey()).isEqualTo("incremental.reeval");
        assertThat(seed.description()).isNotBlank();
        JsonNode doc;
        try {
            doc = new ObjectMapper().readTree(seed.json());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(doc.path("minScoreGap").asDouble()).isEqualTo(0.5);
        assertThat(doc.path("linkMinIntervalMinutes").asInt()).isEqualTo(10);
        assertThat(doc.path("scanWindowHours").asInt()).isEqualTo(24);
        assertThat(doc.path("eventBufferSeconds").asInt()).isEqualTo(20);
        assertThat(doc.path("minImportance").asText()).isEqualTo("HIGH");
    }
}
