package com.info.platform.interfaces.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.policy.PolicyDetailView;
import com.info.platform.application.policy.PolicyListView;
import com.info.platform.application.policy.PolicyService;
import com.info.platform.application.policy.PolicyView;
import com.info.platform.application.policy.RelatedSubjectView;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PolicyController 切片测试（T24）：GET /api/v1/policies（列表）+ GET /api/v1/policies/{id}（详情/404）。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文、不跑 JwtAuthFilter），
 * {@link PolicyService} 用 Mockito mock。认证上下文在 @BeforeEach 经 {@link UserContext#set} 模拟（生产由
 * JwtAuthFilter 写入）， @AfterEach 清空（同 RecommendationControllerTest）。
 */
class PolicyControllerTest {

    private MockMvc mockMvc;
    private PolicyService policyService;

    @BeforeEach
    void setUp() {
        policyService = mock(PolicyService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new PolicyController(policyService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(1L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void list_returns200WithPoliciesAndNextCursor() throws Exception {
        // Arrange
        when(policyService.listPolicies(7, null, null))
                .thenReturn(
                        new PolicyListView(
                                List.of(
                                        new PolicyView(
                                                1L,
                                                "国务院关于白酒的意见",
                                                "国务院政策",
                                                "2026-09-20",
                                                null,
                                                List.of("白酒"))),
                                2L));

        // Act + Assert：200 + code=0 + 列表项 + nextCursor
        mockMvc.perform(get("/api/v1/policies").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.policies[0].title").value("国务院关于白酒的意见"))
                .andExpect(jsonPath("$.data.policies[0].source").value("国务院政策"))
                .andExpect(jsonPath("$.data.policies[0].publishedAt").value("2026-09-20"))
                .andExpect(jsonPath("$.data.policies[0].relatedIndustries[0]").value("白酒"))
                .andExpect(jsonPath("$.data.nextCursor").value(2));
    }

    @Test
    void list_withIndustryAndCursor_passesParams() throws Exception {
        // Arrange：industry + cursor 透传 service
        when(policyService.listPolicies(7, "白酒", 5L))
                .thenReturn(new PolicyListView(List.of(), null));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/policies")
                                .param("days", "7")
                                .param("industry", "白酒")
                                .param("cursor", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policies").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void detail_returns200WithRelatedSubjectsAndAiTendency() throws Exception {
        // Arrange：详情含关联自选标的 + ai_tendency=0
        when(policyService.getPolicy(1L, 1L))
                .thenReturn(
                        new PolicyDetailView(
                                1L,
                                "国务院关于白酒的意见",
                                "国务院政策",
                                "2026-09-20",
                                null,
                                List.of("白酒"),
                                "https://gov/a",
                                0,
                                List.of(new RelatedSubjectView("SH600519", "贵州茅台", "白酒"))));

        // Act + Assert
        mockMvc.perform(get("/api/v1/policies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.aiTendency").value(0))
                .andExpect(jsonPath("$.data.relatedIndustries[0]").value("白酒"))
                .andExpect(jsonPath("$.data.relatedSubjects[0].subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data.relatedSubjects[0].subjectName").value("贵州茅台"))
                .andExpect(jsonPath("$.data.relatedSubjects[0].industry").value("白酒"));
    }

    @Test
    void detail_notFound_returns404With30040() throws Exception {
        // Arrange：条目不存在 → BusinessException(POLICY_NOT_FOUND) → GlobalExceptionHandler 映射 404 +
        // 30040
        when(policyService.getPolicy(999L, 1L))
                .thenThrow(new BusinessException(ErrorCode.POLICY_NOT_FOUND));

        // Act + Assert
        mockMvc.perform(get("/api/v1/policies/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30040));
    }
}
