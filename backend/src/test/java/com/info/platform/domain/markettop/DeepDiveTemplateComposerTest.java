package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveTemplateComposer.TemplateFacts;
import org.junit.jupiter.api.Test;

/**
 * DeepDiveTemplateComposer 单测（M21 T182，方案 §4.4.6 模板兜底）：确定性中性文案——数字全部来自结构化输入（零 LLM 叙述）， 有事件/无事件两形态 +
 * 免责尾句常驻。
 */
class DeepDiveTemplateComposerTest {

    private static final TemplateFacts FACTS =
            new TemplateFacts(
                    10,
                    6,
                    "HIGH",
                    "2026-09-21",
                    "机械设备",
                    3,
                    81.25,
                    29.4,
                    55.1,
                    70.0,
                    0.40,
                    0.20,
                    0.20,
                    0.20);

    @Test
    void compose_withEvents_numbersAllFromInput() {
        String text = DeepDiveTemplateComposer.compose(FACTS);

        assertThat(text)
                .contains("近10日")
                .contains("6 条关联事件")
                .contains("最高重要度 HIGH")
                .contains("最新 2026-09-21")
                .contains("所属行业 机械设备")
                .contains("24h 热度排名第 3")
                .contains("81.3/29.4/55.1/70.0")
                .contains("0.40|0.20|0.20|0.20")
                .contains("以上为数据整理，不构成投资建议");
    }

    @Test
    void compose_withoutEvents_honestWording() {
        TemplateFacts noEvents =
                new TemplateFacts(
                        10, 0, null, null, "机械设备", 0, 0, 0, 50.0, 70.0, 0.40, 0.20, 0.20, 0.20);

        String text = DeepDiveTemplateComposer.compose(noEvents);

        assertThat(text).contains("近10日无关联事件").doesNotContain("最高重要度 null");
        assertThat(text).contains("以上为数据整理，不构成投资建议");
    }

    @Test
    void compose_industryMissing_noFabricatedIndustry() {
        TemplateFacts noIndustry =
                new TemplateFacts(
                        10,
                        3,
                        "MEDIUM",
                        "2026-09-20",
                        null,
                        0,
                        10,
                        0,
                        50.0,
                        70.0,
                        0.40,
                        0.20,
                        0.20,
                        0.20);

        String text = DeepDiveTemplateComposer.compose(noIndustry);

        // 行业未归属如实标注，不编造行业名；热度排名缺省 0 = 不出现排名段
        assertThat(text).contains("所属行业未归属").doesNotContain("热度排名第 0");
    }
}
