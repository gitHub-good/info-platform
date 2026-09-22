package com.info.platform.interfaces.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.LlmConfigFacade;
import com.info.platform.application.ai.LlmConfigFacade.LlmApiKeyWrite;
import com.info.platform.application.ai.LlmConfigFacade.LlmConnectivityResult;
import com.info.platform.application.ai.LlmConfigFacade.LlmGlobalUpdate;
import com.info.platform.application.ai.LlmConfigFacade.LlmProviderUpdate;
import com.info.platform.application.ai.LlmConfigView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * LlmConfigController 切片测试（T35）：契约形状（GET 脱敏视图/effectiveMode）+ 写接口透传（合并请求体）+ 业务错误码 映射（30060→404 /
 * 30064→503 / 30065→409 / 2001→400）。
 *
 * <p>对齐 LlmCostReportControllerTest 模式：standaloneSetup 独立 MockMvc，应用端口 Mockito mock， {@link
 * GlobalExceptionHandler} 作 ControllerAdvice（JWT 保护由生产过滤器承担，此处不重复测）。
 */
class LlmConfigControllerTest {

    private MockMvc mockMvc;
    private LlmConfigFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(LlmConfigFacade.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new LlmConfigController(facade))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static LlmConfigView sampleView() {
        LlmConfigView.GlobalConfigView global =
                new LlmConfigView.GlobalConfigView(
                        30,
                        1,
                        20000,
                        0.8,
                        3600,
                        Map.of("brief-type-1", 3600L),
                        1000,
                        8400,
                        "2026-09-22T01:00:00Z",
                        Map.of(
                                "dailyTokenBudgetPerUser", "LIVE",
                                "cacheMaximumSize", "RESTART"));
        LlmConfigView.ProviderConfigView deepseek =
                new LlmConfigView.ProviderConfigView(
                        "deepseek",
                        "deepseek-flash",
                        true,
                        true,
                        "glm",
                        "https://api.deepseek.com",
                        "RESTART",
                        1.0,
                        4.0,
                        new LlmConfigView.ApiKeyView("CONFIGURED", "ENV", "abcd"),
                        "2026-09-22T01:00:00Z",
                        Map.of("baseUrl", "RESTART", "model", "LIVE"));
        return new LlmConfigView(global, List.of(deepseek), true);
    }

    @Test
    void view_returns200WithMaskedKeyAndEffectiveModes() throws Exception {
        when(facade.view()).thenReturn(sampleView());

        mockMvc.perform(get("/api/v1/llm-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.global.timeoutSeconds").value(30))
                .andExpect(jsonPath("$.data.global.cacheMaximumSize").value(1000))
                .andExpect(jsonPath("$.data.global.todayUsedTokens").value(8400))
                .andExpect(
                        jsonPath("$.data.global.effectiveModes.cacheMaximumSize").value("RESTART"))
                .andExpect(jsonPath("$.data.providers[0].name").value("deepseek"))
                .andExpect(jsonPath("$.data.providers[0].baseUrlEffective").value("RESTART"))
                // key 只回脱敏态，无明文字段
                .andExpect(jsonPath("$.data.providers[0].apiKey.status").value("CONFIGURED"))
                .andExpect(jsonPath("$.data.providers[0].apiKey.source").value("ENV"))
                .andExpect(jsonPath("$.data.providers[0].apiKey.last4").value("abcd"))
                .andExpect(jsonPath("$.data.apiKeyWriteEnabled").value(true));
    }

    @Test
    void updateGlobal_passesPartialFieldsAndReturnsUpdatedView() throws Exception {
        when(facade.updateGlobal(any(LlmGlobalUpdate.class))).thenReturn(sampleView().global());

        mockMvc.perform(
                        patch("/api/v1/llm-config/global")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"dailyTokenBudgetPerUser":5000,
                                         "expectedUpdatedAt":"2026-09-22T01:00:00Z"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dailyTokenBudgetPerUser").value(20000));

        org.mockito.ArgumentCaptor<LlmGlobalUpdate> captor =
                org.mockito.ArgumentCaptor.forClass(LlmGlobalUpdate.class);
        org.mockito.Mockito.verify(facade).updateGlobal(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().dailyTokenBudgetPerUser())
                .isEqualTo(5000L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().expectedUpdatedAt())
                .isEqualTo("2026-09-22T01:00:00Z");
    }

    @Test
    void updateProvider_conflictMapsTo409() throws Exception {
        when(facade.updateProvider(eq("deepseek"), any(LlmProviderUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.CONFIG_CONFLICT));

        mockMvc.perform(
                        put("/api/v1/llm-config/providers/deepseek")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30065));
    }

    @Test
    void updateProvider_unknownMapsTo404() throws Exception {
        when(facade.updateProvider(eq("nope"), any(LlmProviderUpdate.class)))
                .thenThrow(new BusinessException(ErrorCode.LLM_PROVIDER_NOT_FOUND));

        mockMvc.perform(
                        put("/api/v1/llm-config/providers/nope")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"model\":\"m\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30060));
    }

    @Test
    void writeApiKey_secretMissingMapsTo503() throws Exception {
        when(facade.writeApiKey(eq("deepseek"), any(LlmApiKeyWrite.class)))
                .thenThrow(new BusinessException(ErrorCode.API_KEY_WRITE_DISABLED));

        mockMvc.perform(
                        put("/api/v1/llm-config/providers/deepseek/api-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"apiKey\":\"sk-secret\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(30064));
    }

    @Test
    void connectivityTest_returns200WithResultBody() throws Exception {
        when(facade.connectivityTest("deepseek"))
                .thenReturn(new LlmConnectivityResult(true, 812L, "deepseek-flash", null));

        mockMvc.perform(post("/api/v1/llm-config/providers/deepseek/connectivity-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.latencyMillis").value(812))
                .andExpect(jsonPath("$.data.model").value("deepseek-flash"));
    }

    @Test
    void updateGlobal_invalidValueMapsTo400WithFieldReason() throws Exception {
        when(facade.updateGlobal(any(LlmGlobalUpdate.class)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.PARAM_INVALID,
                                "budgetWarnRatio: 取值须为 (0.0, 1.0]，当前值=1.5"));

        mockMvc.perform(
                        patch("/api/v1/llm-config/global")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"budgetWarnRatio\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("budgetWarnRatio: 取值须为 (0.0, 1.0]，当前值=1.5"));
    }
}
