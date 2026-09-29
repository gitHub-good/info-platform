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
        // Arrange + Act：加载启用模板（V38 播种 status=1 v2.0，v1.0 置废）
        PromptTemplate template = promptTemplateService.loadActiveTemplate(BriefType.L1_CLASSIFY);

        // Assert：版本/分段/占位符化枚举清单（v2.0 = {{industryEnums}} 注入 + 容器恒注入）与代码权威同源
        assertThat(template.getVersion()).isEqualTo("v2.0");
        assertThat(template.getTemplate())
                .contains("{{industryEnums}}")
                .contains("{{marketLabel}}");
        for (String container : IndustryCategory.CONTAINERS) {
            assertThat(template.getTemplate()).contains(container);
        }
        assertThat(template.getTemplate()).contains("---SYSTEM---").contains("---USER---");
        assertThat(template.getTemplate()).contains("json"); // DeepSeek JSON mode 前提（V2 校验不变量）

        // 占位符渲染：v2.0 四键注入即替换（治理页热生效链路通）
        List<ChatMessage> messages =
                promptTemplateService.render(
                        template,
                        Map.of(
                                "batchSize", "1",
                                "items",
                                        "{\"id\":1,\"title\":\"t\",\"summary\":\"s\",\"source\":\"src\",\"companies\":[]}",
                                "marketLabel", "港股",
                                "industryEnums",
                                        "港股行业枚举（31+1 个，东财 F10 口径）：银行、软件服务、UNKNOWN（行业未知兜底）"));
        assertThat(messages).hasSize(2);
        // 枚举清单注入在 SYSTEM 段；用户段携带市场口径标注与条目行
        assertThat(messages.get(0).content())
                .contains("港股行业枚举")
                .doesNotContain("{{industryEnums}}");
        assertThat(messages.get(1).content())
                .contains("\"id\":1")
                .contains("本批市场口径：港股")
                .doesNotContain("{{items}}")
                .doesNotContain("{{marketLabel}}");
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
