package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * V25 提示词播种集成测试（T124，ADR-0046 裁决 3）：brief_type=7 v1.0 启用模板可加载、日报三占位符可渲染、数字约束与免责红线在
 * 模板文本、治理页零特例自动可见。内存库 Flyway 播种即验证（只读，无清理诉求）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyReportPromptTemplateSeedTest {

    @Autowired private PromptTemplateRepository promptTemplateRepository;

    @Autowired private PromptTemplateService promptTemplateService;

    @Autowired private PromptTemplateAdminService adminService;

    @Test
    void seed_industryDailyTemplate_activeAndRenderable() {
        // Arrange + Act：加载启用模板（V25 播种 status=1 v1.0）
        PromptTemplate template =
                promptTemplateService.loadActiveTemplate(BriefType.INDUSTRY_DAILY);

        // Assert：版本/分段/统计注入约束（幻觉防线）与免责红线
        assertThat(template.getVersion()).isEqualTo("v1.0");
        assertThat(template.getTemplate())
                .contains("---SYSTEM---")
                .contains("---USER---")
                .contains("json") // DeepSeek JSON mode 前提
                .contains("不得引入未提供的数字或事件")
                .contains("不给买卖建议")
                .contains("AI 分析仅供参考");

        // 占位符渲染：reportDate/industryStats/topEvents 注入即替换（治理页热生效链路通）
        List<ChatMessage> messages =
                promptTemplateService.render(
                        template,
                        Map.of(
                                "reportDate", "2026-09-22",
                                "industryStats", "银行=资讯12/事件1",
                                "topEvents", "1. [HIGH|BULLISH] 银行 | 央行逆回购"));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content())
                .contains("报告日期：2026-09-22")
                .contains("银行=资讯12/事件1")
                .doesNotContain("{{reportDate}}")
                .doesNotContain("{{industryStats}}")
                .doesNotContain("{{topEvents}}");
    }

    @Test
    void governance_industryDailyGroupAutoVisible() {
        // 治理页零特例：BriefType 扩码后分组自动出现（ADR-0046 裁决 3——页面零改动）
        assertThat(adminService.list().groups())
                .anySatisfy(
                        group -> {
                            assertThat(group.briefType())
                                    .isEqualTo(BriefType.INDUSTRY_DAILY.code());
                            assertThat(group.name()).isEqualTo("行业日报");
                            assertThat(group.activeCount()).isEqualTo(1); // v1.0 种子启用
                        });
    }
}
