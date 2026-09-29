package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.ChatMessage;
import com.info.platform.domain.ai.PromptTemplate;
import com.info.platform.domain.analysis.IndustryCategory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * V39 提示词播种集成测试（M29 T254，L1PromptTemplateSeedTest 同款）：brief_type=6 v1.1 启用模板可加载、分市场 affected
 * 枚举清单与代码权威同源、 占位符可渲染、v1.0 置废。内存库 Flyway 播种即验证（只读，无清理诉求）。
 */
@SpringBootTest
@ActiveProfiles("test")
class L2MarketPromptTemplateSeedTest {

    @Autowired private PromptTemplateService promptTemplateService;

    @Test
    void seed_l2ExtractV11_activeAndRenderableWithMarketEnums() {
        // Arrange + Act：加载启用模板（V39 播种 status=1 v1.1，v1.0 置废）
        PromptTemplate template = promptTemplateService.loadActiveTemplate(BriefType.L2_EXTRACT);

        // Assert：版本/占位符化（v1.1 = {{industryEnums}} 按市场注入 + {{marketLabel}} 口径标注）
        assertThat(template.getVersion()).isEqualTo("v1.1");
        assertThat(template.getTemplate())
                .contains("{{industryEnums}}")
                .contains("{{marketLabel}}");
        assertThat(template.getTemplate()).contains("---SYSTEM---").contains("---USER---");
        assertThat(template.getTemplate()).contains("json"); // DeepSeek JSON mode 前提（V2 校验不变量）

        // 占位符渲染：五键注入即替换（治理页热生效链路通）——规则文本不残留未替换占位符
        Map<String, String> ctx = new HashMap<>();
        ctx.put("today", "2026-09-29");
        ctx.put("batchSize", "1");
        ctx.put("items", "{\"id\":1}");
        ctx.put("marketLabel", ClassificationService.marketLabelOf(Market.HK));
        ctx.put(
                "industryEnums",
                "港股行业枚举（31 个，东财 F10 口径）：" + String.join("、", IndustryCategory.HK_INDUSTRIES));
        List<ChatMessage> messages = promptTemplateService.render(template, ctx);
        String user = messages.get(1).content();
        assertThat(user).contains("本批市场口径：港股").doesNotContain("{{marketLabel}}");
        // V39 模板 SYSTEM 段承载 {{industryEnums}}——HK 渲染不含申万措辞
        assertThat(messages.get(0).content()).contains("软件服务").doesNotContain("申万一级行业");
    }
}
