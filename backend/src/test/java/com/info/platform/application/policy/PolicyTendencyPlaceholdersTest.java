package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.policy.PolicyItem;
import com.info.platform.domain.policy.PolicyRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 政策解读占位符同源闸门（T46 / ADR-0022）：{@code buildContext} 实际注入键集 == {@code provided()} 描述符键集。
 *
 * <p>本测试须与 {@link PolicyTendencyService} 同包（buildContext 包内可见）；装配器加/删键漏登记描述符 → 本测试红。
 */
class PolicyTendencyPlaceholdersTest {

    @Test
    void buildContext_keySetEqualsProvidedKeySet() {
        // Arrange：代表性政策条目（全字段给值）
        PolicyTendencyService service =
                new PolicyTendencyService(
                        mock(PolicyRepository.class),
                        mock(LlmGateway.class),
                        mock(com.info.platform.application.ai.PromptTemplateService.class),
                        mock(BriefContentCodec.class));
        PolicyItem item =
                PolicyItem.create(
                        "关于印发xxx的通知",
                        "国务院",
                        LocalDate.of(2026, 9, 1),
                        "政策摘要",
                        List.of("半导体", "新能源"),
                        "https://www.gov.cn/x");

        // Act
        List<String> providedKeys =
                service.provided().stream().map(PlaceholderDescriptor::key).toList();

        // Assert：7 键逐一对齐（同序），场景归属为政策解读
        assertThat(PolicyTendencyService.buildContext(item).keySet())
                .containsExactlyElementsOf(providedKeys)
                .hasSize(7);
        assertThat(service.briefTypes()).containsExactly(BriefType.POLICY);
    }
}
