package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V32 模板播种集成断言（M21 T182，方案 §4.4.1）：brief_type=10 v1.0 启用行就位、SYSTEM/USER 分段与全部 9 占位符（渲染不残留 {@code
 * {{key}}}）、红线语句进模板（引用限定 + 不给买卖建议——机制防线在五步校验链）、单行唯一。
 */
@SpringBootTest
@ActiveProfiles("test")
class V32DeepDiveTemplateSeedTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void v32_seedsActiveDeepDiveTemplate() {
        Integer status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM prompt_template WHERE brief_type = 10 AND version = 'v1.0'",
                        Integer.class);
        String template =
                jdbcTemplate.queryForObject(
                        "SELECT template FROM prompt_template WHERE brief_type = 10 AND version = 'v1.0'",
                        String.class);

        assertThat(status).isEqualTo(1);
        assertThat(template).contains("---SYSTEM---").contains("---USER---");
        assertThat(template)
                .contains(
                        "{{subject}}",
                        "{{factors}}",
                        "{{totalScore}}",
                        "{{percentile}}",
                        "{{breakthrough}}",
                        "{{topEvents}}",
                        "{{relatedNews}}",
                        "{{industryNews}}",
                        "{{marketSnapshot}}");
        // 红线语句进模板（提示词自觉面；机制防线在 CitationReconciler/ProhibitedPhraseScanner）
        assertThat(template)
                .contains("只能引用输入中给出的 eventId/newsId")
                .contains("不得出现任何投资指令或收益承诺")
                .contains("不构成投资建议");
    }

    @Test
    void v32_singleRow_onlyBriefType10() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM prompt_template WHERE brief_type = 10",
                        Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
