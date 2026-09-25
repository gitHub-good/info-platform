package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.domain.analysis.ImportanceScorer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PipelineRuntimeConfigSeeder 单测（T120~T125，方案 §4.8 键表）：pipeline.global/pipeline.l0/pipeline.l2
 * 三键结构与缺省值——种子 JSON 与 ImportanceScorer 缺省参数同源（热改回落基准一致，防两处漂移）。AAA 结构。
 */
class PipelineRuntimeConfigSeederTest {

    private final PipelineRuntimeConfigSeeder seeder =
            new PipelineRuntimeConfigSeeder(new ObjectMapper());

    private RuntimeConfigSeed seedOf(String key) {
        return seeder.seeds().stream()
                .filter(seed -> seed.configKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺 " + key + " 种子"));
    }

    @Test
    void seeds_carriesThreePipelineKeys() {
        List<String> keys = seeder.seeds().stream().map(RuntimeConfigSeed::configKey).toList();
        assertThat(keys).containsExactly("pipeline.global", "pipeline.l0", "pipeline.l2");
    }

    @Test
    void seed_l2Params_alignWithScorerDefaults() {
        String json = seedOf("pipeline.l2").json();

        assertThat(json)
                .contains("\"l2BatchSize\":10")
                .contains("\"threshold\":2.5")
                .contains("\"quotaRatio\":0.2")
                .contains("\"subjectBonus\":1.5")
                .contains("\"快讯\":1.5");
        // 触发词表与 ImportanceScorer 缺省同源（强弱词逐词对齐）
        for (String trigger : ImportanceScorer.defaults().strongTriggers()) {
            assertThat(json).contains("\"" + trigger + "\"");
        }
        for (String trigger : ImportanceScorer.defaults().mediumTriggers()) {
            assertThat(json).contains("\"" + trigger + "\"");
        }
    }
}
