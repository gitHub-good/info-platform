package com.info.platform.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** TopRecommendation 单测（T23）：紧凑构造器归一化（null 字段→空串、负 rank→0）。AAA 结构。 领域层纯净值对象，脱离容器单测。 */
class TopRecommendationTest {

    @Test
    void construct_normal_preservesFields() {
        // Act
        TopRecommendation t = new TopRecommendation("SH600519", "贵州茅台", "信息面活跃", 1);

        // Assert
        assertThat(t.subjectCode()).isEqualTo("SH600519");
        assertThat(t.subjectName()).isEqualTo("贵州茅台");
        assertThat(t.reason()).isEqualTo("信息面活跃");
        assertThat(t.rank()).isEqualTo(1);
    }

    @Test
    void construct_nullFields_normalizedToEmpty() {
        // Act：模型可能给出 null 字段（缺 reason/code/name）
        TopRecommendation t = new TopRecommendation(null, null, null, 2);

        // Assert：归一化为空串（防 NPE，解析兜底）
        assertThat(t.subjectCode()).isEmpty();
        assertThat(t.subjectName()).isEmpty();
        assertThat(t.reason()).isEmpty();
        assertThat(t.rank()).isEqualTo(2);
    }

    @Test
    void construct_negativeRank_normalizedToZero() {
        // Act：模型给出负 rank（异常输出）
        TopRecommendation t = new TopRecommendation("SH600519", "贵州茅台", "活跃", -3);

        // Assert：负值归 0（防排序异常）
        assertThat(t.rank()).isZero();
    }
}
