package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * V29 提示词播种集成测试（M17 T145，brief_type=9 行业周报）：启用模板可加载、六占位符可渲染、置信度规则层锁定与合规红线（不给买卖建议 /
 * 不预测点位）在模板文本、治理页零特例自动可见（M5 体系，8→9 类）。
 */
@SpringBootTest
@ActiveProfiles("test")
class WeeklyReportPromptTemplateSeedTest {

    @Autowired private PromptTemplateService promptTemplateService;

    @Test
    void seed_industryWeeklyTemplate_activeAndRenderable() {
        PromptTemplate template =
                promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_WEEKLY);

        assertThat(template.getVersion()).isEqualTo("v1.0");
        assertThat(template.getTemplate())
                .contains("---SYSTEM---")
                .contains("---USER---")
                .contains("json")
                .contains("置信度")
                .contains("不得引入未提供的数字或事件")
                .contains("不给买卖建议")
                .contains("不预测")
                .contains("AI 分析仅供参考");

        List<ChatMessage> messages =
                promptTemplateService.render(
                        template,
                        Map.of(
                                "weekStart", "2026-09-21",
                                "weekEnd", "2026-09-27",
                                "heatStats", "银行=资讯12/事件3",
                                "topEvents", "1. [HIGH|BULLISH] 银行 | 央行降准",
                                "policyLines", "1. 央行降准 0.5pct",
                                "trendSignals", "银行: 升温 +100% 置信度HIGH(规则层锁定)"));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content())
                .contains("2026-09-21")
                .contains("央行降准")
                .contains("置信度HIGH")
                .doesNotContain("{{weekStart}}")
                .doesNotContain("{{trendSignals}}");
    }
}
