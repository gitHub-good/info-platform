package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.domain.analysis.ImportanceScorer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PipelineRuntimeConfigSeeder 单测（T120~T125，方案 §4.8
 * 键表）：pipeline.global/pipeline.l0/pipeline.l2/pipeline.budget/pipeline.heat 五键结构与缺省值——种子 JSON 与
 * ImportanceScorer/HeatCalculator 缺省参数同源（热改回落基准一致，防两处漂移）。AAA 结构。
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
    void seeds_carriesFivePipelineKeys() {
        List<String> keys = seeder.seeds().stream().map(RuntimeConfigSeed::configKey).toList();
        assertThat(keys)
                .containsExactly(
                        "pipeline.global",
                        "pipeline.l0",
                        "pipeline.l2",
                        "pipeline.budget",
                        "pipeline.heat");
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

    // ---- T125：pipeline.budget 护栏预算键（方案 §3.5/§4.8） ----

    @Test
    void seed_budget_calibratedForThirtySourcesWithTwoStageRatios() {
        String json = seedOf("pipeline.budget").json();

        // T157 校准（M17 遗留 ⑥）：¥2.60/日 = 2,600,000 微元——scene5-8 近 3 日日均 ¥0.4293 × 30/22 = ¥0.5854
        // 常态水位 22.5%，期内最高日 ¥0.8983 × 30/22 = ¥1.2250 < 降级线 ¥1.56（27% 裕量）；
        // 60%/90% 两级比例零变更（护栏语义不变）；校准初值 1100 归 Guard 每日写回；口径升版 cost-v2:m18-30src
        assertThat(json)
                .contains("\"dailyBudgetMicros\":2600000")
                .contains("\"degradeRatio\":0.6")
                .contains("\"fuseRatio\":0.9")
                .contains("\"calibratedPerItemMicros\":1100")
                .contains("\"costBasis\":\"cost-v2:m18-30src\"");
    }

    // ---- T123：pipeline.heat 热度参数键（方案 §3.6/§4.8） ----

    @Test
    void seed_heat_defaultsAlignWithCalculator() {
        String json = seedOf("pipeline.heat").json();

        // K1=10（裁决 6）/impCoef 1.0-0.5-0.25/双窗半衰期 12|48——与 HeatCalculator.defaults 同源
        assertThat(json)
                .contains("\"k1\":10.0")
                .contains("\"impHigh\":1.0")
                .contains("\"impMedium\":0.5")
                .contains("\"impLow\":0.25")
                .contains("\"halfLifeHours24\":12.0")
                .contains("\"halfLifeHours7\":48.0")
                .contains("\"snapshotIntervalMinutes\":30");
    }
}
