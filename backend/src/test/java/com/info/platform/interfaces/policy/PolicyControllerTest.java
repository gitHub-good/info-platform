package com.info.platform.interfaces.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.policy.PolicyDetailView;
import com.info.platform.application.policy.PolicyListView;
import com.info.platform.application.policy.PolicyPagedView;
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

    // ==================== M9 页码模式契约（T60/T62，REQ-20260925-06 方案 §4.1 / ADR-0035）
    // ====================
    // 修前红锚点：实现前这些用例必须红（旧控制器忽略 page/size/keyword → 200 游标形态）；实现后转绿。

    @Test
    void pageMode_pageWithCursor_mutexRejected400() throws Exception {
        // page 与 cursor 互斥（双模式防呆）→ 400/2001
        mockMvc.perform(get("/api/v1/policies").param("page", "1").param("cursor", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void pageMode_sizeWithoutPage_rejected400() throws Exception {
        // size 仅页码模式可用，缺 page → 400/2001（msg 注明缺 page）
        mockMvc.perform(get("/api/v1/policies").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    void pageMode_sizeOverLimit_rejected400() throws Exception {
        // size 1~50，超限 400 拒绝不截断（ADR-0035）
        mockMvc.perform(get("/api/v1/policies").param("page", "1").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void pageMode_pageZero_rejected400() throws Exception {
        // page ≥ 1，page=0 → 400
        mockMvc.perform(get("/api/v1/policies").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void pageMode_returnsTotalAndEchoedPageSize() throws Exception {
        // Arrange：页码模式走 listPoliciesPaged（days/industry 透传）
        when(policyService.listPoliciesPaged(7, "白酒", null, 2, 10))
                .thenReturn(
                        new PolicyPagedView(
                                List.of(
                                        new PolicyView(
                                                2L,
                                                "白酒产业政策",
                                                "国务院政策",
                                                "2026-09-21",
                                                null,
                                                List.of("白酒"))),
                                11L,
                                2,
                                10));

        // Act + Assert：页码模式响应形态：{policies[], total, page, size}，无 nextCursor（两 record 不混装）
        mockMvc.perform(
                        get("/api/v1/policies")
                                .param("days", "7")
                                .param("industry", "白酒")
                                .param("page", "2")
                                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.policies[0].title").value("白酒产业政策"))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void pageMode_defaultPageSize_is20() throws Exception {
        // Arrange：size 缺省 20（PageQuery.DEFAULT_SIZE），不传 size 也能 stub 命中
        when(policyService.listPoliciesPaged(7, null, null, 1, 20))
                .thenReturn(new PolicyPagedView(List.of(), 0L, 1, 20));

        // Act + Assert
        mockMvc.perform(get("/api/v1/policies").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    void pageMode_outOfRangePage_returns200EmptyListWithEcho() throws Exception {
        // Arrange：越界页（total=8、page=99）→ 200 + 空列表 + 如实回显（ADR-0035 裁决）
        when(policyService.listPoliciesPaged(7, null, null, 99, 20))
                .thenReturn(new PolicyPagedView(List.of(), 8L, 99, 20));

        // Act + Assert
        mockMvc.perform(get("/api/v1/policies").param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policies").isEmpty())
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.page").value(99))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    // ==================== M9 T62：关键词搜索契约（§4.1） ====================

    @Test
    void keyword_withoutPage_rejected400() throws Exception {
        // keyword 仅页码模式可用，缺 page → 400/2001
        mockMvc.perform(get("/api/v1/policies").param("keyword", "半导体"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void keyword_singleChar_withPage_rejected400() throws Exception {
        // 关键词至少 2 个字符 → 400/2001（防单字全表模糊）
        mockMvc.perform(get("/api/v1/policies").param("page", "1").param("keyword", "芯"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("2")));
    }

    @Test
    void keyword_over64Chars_withPage_rejected400() throws Exception {
        // 关键词最长 64 字符 → 400/2001
        mockMvc.perform(get("/api/v1/policies").param("page", "1").param("keyword", "长".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("64")));
    }

    @Test
    void keyword_boundary64Chars_passedThroughTrimmed() throws Exception {
        // Arrange：恰 64 字符合法（边界值），trim 后透传 service
        String keyword = "半".repeat(64);
        when(policyService.listPoliciesPaged(7, null, keyword, 1, 20))
                .thenReturn(new PolicyPagedView(List.of(), 0L, 1, 20));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/policies")
                                .param("page", "1")
                                .param("keyword", " " + keyword + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void keyword_blank_withPage_treatedAsAbsent() throws Exception {
        // Arrange：keyword trim 后空 = 缺席（不过滤），也不触发「缺 page」400
        when(policyService.listPoliciesPaged(7, null, null, 1, 20))
                .thenReturn(new PolicyPagedView(List.of(), 3L, 1, 20));

        // Act + Assert
        mockMvc.perform(get("/api/v1/policies").param("page", "1").param("keyword", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void keyword_withDaysIndustryPage_fullComboPassedThrough() throws Exception {
        // Arrange：days + industry + keyword + page + size 全组合透传
        when(policyService.listPoliciesPaged(30, "电子", "半导体", 2, 50))
                .thenReturn(new PolicyPagedView(List.of(), 12L, 2, 50));

        // Act + Assert
        mockMvc.perform(
                        get("/api/v1/policies")
                                .param("days", "30")
                                .param("industry", "电子")
                                .param("keyword", "半导体")
                                .param("page", "2")
                                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50));
    }
}
