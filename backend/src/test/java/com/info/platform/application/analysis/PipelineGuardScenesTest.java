package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 护栏 scene 集扩位断言（M16 T132 / M21 T183，方案 §3.5/§4.9）：卡片 LLM（briefType=8 → sceneKey "8"）与全市场深析
 * （briefType=10 → sceneKey "10"）计入管道日成本口径——DEGRADED/FUSED 态直接走模板/降级不调 LLM（成本红线联动）。
 * 包内直读常量（PIPELINE_SCENES 包可见）。
 */
class PipelineGuardScenesTest {

    @Test
    void pipelineScenes_containRecommendationCardAndDeepDiveScenes() {
        assertThat(PipelineGuardService.PIPELINE_SCENES).containsExactly("5", "6", "7", "8", "10");
    }
}
