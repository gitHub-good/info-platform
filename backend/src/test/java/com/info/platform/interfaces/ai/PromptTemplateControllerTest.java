package com.info.platform.interfaces.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.PromptPlaceholderRegistry;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PromptTemplateController 切片测试（T45/T46）：契约形状 + 错误码映射（30066/30067/30068/30069/30070）+ 注册表只读端点。
 *
 * <p>对齐 LlmConfigControllerTest 模式：standaloneSetup 独立 MockMvc，应用端口 Mockito mock， {@link
 * GlobalExceptionHandler} 作 ControllerAdvice（JWT 保护由生产过滤器承担，不入白名单即受保护，此处不重复测）。
 */
class PromptTemplateControllerTest {

    private MockMvc mockMvc;
    private PromptPlaceholderRegistry placeholderRegistry;

    @BeforeEach
    void setUp() {
        placeholderRegistry = mock(PromptPlaceholderRegistry.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new PromptTemplateController(placeholderRegistry))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void placeholders_returnsFourScenariosWithDormantFlagOnEventAttribution() throws Exception {
        // Arrange：注册表按场景返回描述符清单
        for (BriefType type : BriefType.values()) {
            when(placeholderRegistry.byBriefType(type))
                    .thenReturn(List.of(new PlaceholderDescriptor("subjectName", "标的名称")));
        }

        // Act + Assert：4 场景按 briefType 升序；场景 2 dormant=true 带 note，其余无 note 字段
        mockMvc.perform(get("/api/v1/prompt-placeholders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.scenarios.length()").value(4))
                .andExpect(jsonPath("$.data.scenarios[0].briefType").value(1))
                .andExpect(jsonPath("$.data.scenarios[0].name").value("个股简报"))
                .andExpect(jsonPath("$.data.scenarios[0].dormant").value(false))
                .andExpect(jsonPath("$.data.scenarios[0].note").doesNotExist())
                .andExpect(jsonPath("$.data.scenarios[1].briefType").value(2))
                .andExpect(jsonPath("$.data.scenarios[1].dormant").value(true))
                .andExpect(jsonPath("$.data.scenarios[1].note").isNotEmpty())
                .andExpect(jsonPath("$.data.scenarios[3].briefType").value(4))
                .andExpect(jsonPath("$.data.scenarios[0].placeholders[0].key").value("subjectName"))
                .andExpect(
                        jsonPath("$.data.scenarios[0].placeholders[0].description").value("标的名称"));
    }

    @Test
    void placeholders_withBriefTypeFilter_returnsSingleScenario() throws Exception {
        // Arrange
        when(placeholderRegistry.byBriefType(BriefType.POLICY))
                .thenReturn(List.of(new PlaceholderDescriptor("policyTitle", "政策标题")));

        // Act + Assert
        mockMvc.perform(get("/api/v1/prompt-placeholders").param("briefType", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarios.length()").value(1))
                .andExpect(jsonPath("$.data.scenarios[0].briefType").value(3))
                .andExpect(jsonPath("$.data.scenarios[0].dormant").value(false));
    }

    @Test
    void placeholders_invalidBriefType_returns400ParamInvalid() throws Exception {
        // Act + Assert：未知场景码按参数校验口径 400/2001
        mockMvc.perform(get("/api/v1/prompt-placeholders").param("briefType", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void placeholders_noteTextDisclosesDormantScenario() {
        // Assert：休眠披露文案与方案 §4.4.5 一致（无生产触发入口 + 事件占位符无来源）
        assertThat(PromptTemplateController.DORMANT_EVENT_NOTE)
                .contains("无生产触发入口")
                .contains("eventTitle");
    }
}
