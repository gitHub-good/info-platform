package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 护栏 scene 集扩位断言（M16 T132，方案 §3.5/§4.9）：卡片 LLM（briefType=8 → sceneKey "8"）计入管道日成本口径——
 * DEGRADED/FUSED 态推荐卡片直接走模板不调 LLM（成本红线联动）。包内直读常量（PIPELINE_SCENES 包可见）。
 */
class PipelineGuardScenesTest {

    @Test
    void pipelineScenes_containRecommendationCardScene() {
        assertThat(PipelineGuardService.PIPELINE_SCENES).containsExactly("5", "6", "7", "8");
    }
}
