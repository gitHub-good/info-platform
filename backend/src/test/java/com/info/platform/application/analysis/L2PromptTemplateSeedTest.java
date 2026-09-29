package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.analysis.EventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * V24 提示词播种集成测试（T122，ADR-0046 裁决 3）：brief_type=6 v1.0 启用模板可加载、事件类型 9 值清单与代码权威同源、 占位符
 * today/batchSize/items 可渲染、治理页零特例自动可见。内存库 Flyway 播种即验证（只读，无清理诉求）。
 */
@SpringBootTest
@ActiveProfiles("test")
class L2PromptTemplateSeedTest {

    @Autowired private PromptTemplateRepository promptTemplateRepository;

    @Autowired private PromptTemplateService promptTemplateService;

    @Autowired private PromptTemplateAdminService adminService;

    @Test
    void seed_l2ExtractTemplate_activeAndRenderable() {
        // Arrange + Act：加载启用模板（V39 播种 status=1 v1.1，v1.0 置废——M29 T254 按市场注入枚举集）
        PromptTemplate template = promptTemplateService.loadActiveTemplate(BriefType.L2_EXTRACT);

        // Assert：版本/分段/事件类型 9 值与代码权威同源 + 原文约束（幻觉防线）
        assertThat(template.getVersion()).isEqualTo("v1.1");
        for (EventType type : EventType.values()) {
            assertThat(template.getTemplate()).contains(type.name());
        }
        assertThat(template.getTemplate())
                .contains("---SYSTEM---")
                .contains("---USER---")
                .contains("json") // DeepSeek JSON mode 前提
                .contains("不得编造数字、公司或事件")
                .contains("quote")
                .contains("skipped");

        // 占位符渲染：today/batchSize/items 注入即替换（治理页热生效链路通）
        List<ChatMessage> messages =
                promptTemplateService.render(
                        template,
                        Map.of(
                                "today", "2026-09-22",
                                "batchSize", "1",
                                "items",
                                        "{\"id\":1,\"title\":\"t\",\"main\":\"银行\",\"candidates\":[]}"));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content())
                .contains("今日日期：2026-09-22")
                .doesNotContain("{{today}}")
                .doesNotContain("{{items}}");
    }

    @Test
    void governance_l2ExtractGroupAutoVisible() {
        // 治理页零特例：BriefType 扩码后分组自动出现（ADR-0046 裁决 3——页面零改动）
        assertThat(adminService.list().groups())
                .anySatisfy(
                        group -> {
                            assertThat(group.briefType()).isEqualTo(BriefType.L2_EXTRACT.code());
                            assertThat(group.name()).isEqualTo("事件提取");
                            assertThat(group.activeCount()).isEqualTo(1); // v1.0 种子启用
                        });
    }
}
