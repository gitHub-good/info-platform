package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * PromptSections 单测（T45 共享工具，自 PromptTemplateService 等价提取）：分段切分（缺失/顺序/正常）+ 占位符提取去重保序 +
 * 计数。渲染路径等价性由既有 PromptTemplateServiceTest 守护（其不经本类直接断言 render 行为）。
 */
class PromptSectionsTest {

    @Test
    void split_normal_returnsSystemThenUserSections() {
        // Arrange
        String raw = "---SYSTEM---\n你是分析师。\n---USER---\n标的：{{subjectName}}";

        // Act
        String[] sections = PromptSections.split(raw, "v1.0");

        // Assert：两段正文均不含标记行本身
        assertThat(sections).hasSize(2);
        assertThat(sections[0]).isEqualTo("\n你是分析师。\n");
        assertThat(sections[1]).isEqualTo("\n标的：{{subjectName}}");
    }

    @Test
    void split_missingSystemMarker_throwsIllegalState() {
        // Act + Assert：与渲染器同款数据损坏条件（msg 带标记与版本定位）
        assertThatThrownBy(() -> PromptSections.split("---USER---\nu", "v1.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("---SYSTEM---")
                .hasMessageContaining("v1.0");
    }

    @Test
    void split_missingUserMarker_throwsIllegalState() {
        assertThatThrownBy(() -> PromptSections.split("---SYSTEM---\ns", "v1.0"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void split_reversedOrder_throwsIllegalState() {
        assertThatThrownBy(() -> PromptSections.split("---USER---\nu\n---SYSTEM---\ns", "v2.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2.0");
    }

    @Test
    void extractKeys_deduplicatesAndKeepsOrder() {
        // Arrange：同一键多次出现 + 键名前后空白 + 单花括号 JSON 不提取
        String text = "{{subjectName}} {{ price }}\n{{subjectName}}\n{summary, keyEvents[]}";

        // Act + Assert
        assertThat(PromptSections.extractKeys(text)).containsExactly("subjectName", "price");
        assertThat(PromptSections.countKeys(text)).isEqualTo(2);
    }

    @Test
    void extractKeys_nullOrEmpty_returnsEmptyList() {
        assertThat(PromptSections.extractKeys(null)).isEmpty();
        assertThat(PromptSections.extractKeys("")).isEmpty();
        assertThat(PromptSections.countKeys(null)).isZero();
    }
}
