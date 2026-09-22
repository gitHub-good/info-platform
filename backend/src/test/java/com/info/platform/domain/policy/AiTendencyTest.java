package com.info.platform.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link AiTendency} 枚举单测（T28）：{@link AiTendency#fromCode} 容错 + {@link AiTendency#fromBias}
 * 政策倾向标签映射。AAA 结构。
 */
class AiTendencyTest {

    @Test
    void fromCode_knownCode_returnsEnum() {
        assertThat(AiTendency.fromCode(0)).isEqualTo(AiTendency.UNJUDGED);
        assertThat(AiTendency.fromCode(1)).isEqualTo(AiTendency.BULLISH);
        assertThat(AiTendency.fromCode(2)).isEqualTo(AiTendency.BEARISH);
        assertThat(AiTendency.fromCode(3)).isEqualTo(AiTendency.NEUTRAL);
    }

    @Test
    void fromCode_nullOrUnknown_fallsBackToUnjudged() {
        assertThat(AiTendency.fromCode(null)).isEqualTo(AiTendency.UNJUDGED);
        assertThat(AiTendency.fromCode(99)).isEqualTo(AiTendency.UNJUDGED);
    }

    @Test
    void fromBias_exactLabels_returnMatchingTendency() {
        assertThat(AiTendency.fromBias("利好")).isEqualTo(AiTendency.BULLISH);
        assertThat(AiTendency.fromBias("利空")).isEqualTo(AiTendency.BEARISH);
        assertThat(AiTendency.fromBias("中性")).isEqualTo(AiTendency.NEUTRAL);
    }

    @Test
    void fromBias_labelWithPunctuationOrModifier_stillMatchesByContains() {
        // 模型偶发附加标点/修饰，contains 容错
        assertThat(AiTendency.fromBias("利好。")).isEqualTo(AiTendency.BULLISH);
        assertThat(AiTendency.fromBias("整体利空")).isEqualTo(AiTendency.BEARISH);
        assertThat(AiTendency.fromBias(" 偏中性 ")).isEqualTo(AiTendency.NEUTRAL);
    }

    @Test
    void fromBias_nullBlankOrUnknown_returnsUnjudged() {
        assertThat(AiTendency.fromBias(null)).isEqualTo(AiTendency.UNJUDGED);
        assertThat(AiTendency.fromBias("")).isEqualTo(AiTendency.UNJUDGED);
        assertThat(AiTendency.fromBias("   ")).isEqualTo(AiTendency.UNJUDGED);
        assertThat(AiTendency.fromBias("无法判断")).isEqualTo(AiTendency.UNJUDGED);
    }
}
