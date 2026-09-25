package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.ai.PromptTemplateAdminService;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.ai.PromptTemplateRepository;
import com.info.platform.domain.analysis.IndustryCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * V23 提示词播种集成测试（T121，ADR-0046 裁决 3）：brief_type=5 v1.0 启用模板可加载、35 枚举清单与代码权威同源、 占位符可渲染、治理页零特例自动可见
 * （PromptTemplateAdminService 分组出现场景 5）。 内存库 Flyway 播种即验证（只读，无清理诉求）。
 */
@SpringBootTest
@ActiveProfiles("test")
class L1PromptTemplateSeedTest {

    @Autowired private PromptTemplateRepository promptTemplateRepository;

    @Autowired private PromptTemplateService promptTemplateService;

    @Autowired private PromptTemplateAdminService adminService;

    @Test
    void seed_l1ClassifyTemplate_activeAndRenderable() {
        // Arrange + Act：加载启用模板（V23 播种 status=1 v1.0）
        PromptTemplate template = promptTemplateService.loadActiveTemplate(BriefType.L1_CLASSIFY);

        // Assert：版本/分段/枚举清单与代码权威同源
        assertThat(template.getVersion()).isEqualTo("v1.0");
        for (String sw : IndustryCategory.SW_INDUSTRIES) {
            assertThat(template.getTemplate()).contains(sw);
        }
        for (String container : IndustryCategory.CONTAINERS) {
            assertThat(template.getTemplate()).contains(container);
        }
        assertThat(template.getTemplate()).contains("---SYSTEM---").contains("---USER---");
        assertThat(template.getTemplate()).contains("json"); // DeepSeek JSON mode 前提（V2 校验不变量）

        // 占位符渲染：batchSize/items 注入即替换（治理页热生效链路通）
        List<ChatMessage> messages =
                promptTemplateService.render(
                        template,
                        Map.of(
                                "batchSize", "1",
                                "items",
                                        "{\"id\":1,\"title\":\"t\",\"summary\":\"s\",\"source\":\"src\",\"companies\":[]}"));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).contains("\"id\":1").doesNotContain("{{items}}");
    }

    @Test
    void governance_l1ClassifyGroupAutoVisible() {
        // 治理页零特例：BriefType 扩码后分组自动出现（ADR-0046 裁决 3——页面零改动）
        assertThat(adminService.list().groups())
                .anySatisfy(
                        group -> {
                            assertThat(group.briefType()).isEqualTo(BriefType.L1_CLASSIFY.code());
                            assertThat(group.name()).isEqualTo("行业归类");
                            assertThat(group.activeCount()).isEqualTo(1); // v1.0 种子启用
                        });
    }
}
