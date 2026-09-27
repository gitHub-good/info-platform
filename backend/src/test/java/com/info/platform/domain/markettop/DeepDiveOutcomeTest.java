package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveOutcome.GenMethod;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DeepDiveOutcome 单测（M21 T182）：LLM 终态构造（citations 去重并集 + dive_summary ≤160 字拼装）与 TEMPLATE 终态（空
 * highlights/risks/citations——不参与 diveScore）。
 */
class DeepDiveOutcomeTest {

    private static final Citation EVENT_1 = new Citation("EVENT", 1);

    private static final Citation EVENT_2 = new Citation("EVENT", 2);

    private static final Citation NEWS_3 = new Citation("NEWS", 3);

    @Test
    void llm_citationsDedupedUnionOfEntries() {
        Parsed parsed =
                new Parsed(
                        "论点",
                        List.of(
                                new Entry("亮点一", List.of(EVENT_1, EVENT_2)),
                                new Entry("亮点二", List.of(EVENT_1, NEWS_3))),
                        List.of(
                                new Entry("风险一", List.of(EVENT_2)),
                                new Entry("风险二", List.of(EVENT_2))),
                        List.of());

        DeepDiveOutcome outcome = DeepDiveOutcome.llm(parsed);

        assertThat(outcome.method()).isEqualTo(GenMethod.LLM);
        assertThat(outcome.citations()).containsExactly(EVENT_1, EVENT_2, NEWS_3);
        assertThat(outcome.summary()).contains("论点").contains("亮点一").contains("风险一");
    }

    @Test
    void llm_summaryCappedAt160Chars() {
        String longText = "长".repeat(200);
        Parsed parsed =
                new Parsed(
                        longText,
                        List.of(
                                new Entry(longText, List.of(EVENT_1)),
                                new Entry(longText, List.of(EVENT_1))),
                        List.of(
                                new Entry(longText, List.of(EVENT_1)),
                                new Entry(longText, List.of(EVENT_1))),
                        List.of());

        assertThat(DeepDiveOutcome.llm(parsed).summary().length())
                .isLessThanOrEqualTo(DeepDiveOutcome.SUMMARY_MAX_CHARS);
    }

    @Test
    void template_emptyStructureAndDeterministic() {
        DeepDiveOutcome outcome = DeepDiveOutcome.template("近10日 5 条关联事件…不构成投资建议。");

        assertThat(outcome.method()).isEqualTo(GenMethod.TEMPLATE);
        assertThat(outcome.thesis()).contains("不构成投资建议");
        assertThat(outcome.highlights()).isEmpty();
        assertThat(outcome.risks()).isEmpty();
        assertThat(outcome.citations()).isEmpty();
        assertThat(outcome.summary()).isEqualTo(outcome.thesis());
    }
}
