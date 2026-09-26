package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V27 模板播种集成断言（T132，方案 §4.1/§4.5）：brief_type=8 v1.0 启用行就位、模板正文含 SYSTEM/USER 分段与全部 7 占位符（渲染不残留
 * {@code {{key}}}）。护栏 scene 扩 '8' 同批核验（PIPELINE_SCENES 包含型断言在应用层包内单测）。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationTemplateSeedTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void v27_seedsActiveCardTemplate() {
        Integer status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM prompt_template WHERE brief_type = 8 AND version = 'v1.0'",
                        Integer.class);
        String template =
                jdbcTemplate.queryForObject(
                        "SELECT template FROM prompt_template WHERE brief_type = 8 AND version = 'v1.0'",
                        String.class);

        // Assert：status=1 启用（V23/V24/V25 惯例）
        assertThat(status).isEqualTo(1);
        assertThat(template).contains("---SYSTEM---").contains("---USER---");
        assertThat(template)
                .contains(
                        "{{level}}",
                        "{{eventTypeLabel}}",
                        "{{directionLabel}}",
                        "{{summary}}",
                        "{{industries}}",
                        "{{subjects}}",
                        "{{watchSubjects}}");
        // 红线语句进模板（提示词自觉面；机制防线在 FactWhitelistValidator）
        assertThat(template).contains("不得引入输入之外的任何事实");
    }

    @Test
    void v27_singleRow_onlyBriefType8() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM prompt_template WHERE brief_type = 8", Integer.class);

        // Assert：UNIQUE(brief_type, version) 幂等——单行
        assertThat(count).isEqualTo(1);
    }
}
