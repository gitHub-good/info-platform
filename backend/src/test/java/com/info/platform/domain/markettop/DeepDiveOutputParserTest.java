package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DeepDiveOutputParser 结构校验单测（M21 T182，方案 §4.4.4 步 2）：thesis 非空 ≤120 字 / highlights 恰 2~4 / risks 恰
 * 2~3 / 每条 ≤80 字非空——结构缺字段或越界一律拒（→ 模板兜底）。JSON 解析容错面在基础设施实现单测。
 */
class DeepDiveOutputParserTest {

    private static final Citation CITATION = new Citation("EVENT", 1);

    private static Entry entry(String text) {
        return new Entry(text, List.of(CITATION));
    }

    private static Parsed parsed(String thesis, int highlights, int risks) {
        return new Parsed(thesis, entries(highlights), entries(risks), List.of());
    }

    private static List<Entry> entries(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> entry("条目" + i)).toList();
    }

    @Test
    void structurallyValid_minimalShape_valid() {
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 2, 2))).isTrue();
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 4, 3))).isTrue();
    }

    @Test
    void structurallyValid_thesisBlankOrOver120_rejected() {
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("", 2, 2))).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(parsed(" ".repeat(10), 2, 2))).isFalse();
        assertThat(
                        DeepDiveOutputParser.structurallyValid(
                                parsed(
                                        "长".repeat(DeepDiveOutputParser.THESIS_MAX_CHARS + 1),
                                        2,
                                        2)))
                .isFalse();
    }

    @Test
    void structurallyValid_entryCountsOutOfRange_rejected() {
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 1, 2))).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 5, 2))).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 2, 1))).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(parsed("论点", 2, 4))).isFalse();
    }

    @Test
    void structurallyValid_entryTextOver80OrBlank_rejected() {
        String over = "长".repeat(DeepDiveOutputParser.ENTRY_MAX_CHARS + 1);
        Parsed highlightOver =
                new Parsed("论点", List.of(entry(over), entry("正常")), entries(2), List.of());
        Parsed riskBlank =
                new Parsed("论点", entries(2), List.of(entry("  "), entry("正常")), List.of());
        Parsed entriesNull = new Parsed("论点", null, null, List.of());

        assertThat(DeepDiveOutputParser.structurallyValid(highlightOver)).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(riskBlank)).isFalse();
        assertThat(DeepDiveOutputParser.structurallyValid(entriesNull)).isFalse();
    }
}
