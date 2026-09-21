package com.info.platform.interfaces.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.ai.AIBriefService;
import com.info.platform.application.ai.AIBriefView;
import com.info.platform.domain.ai.BriefContent;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.common.UserContext;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AIBriefController 切片测试（T21）：POST 202 / GET 200/404 / 成本上限 429 / 参数校验。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文、不跑 JwtAuthFilter），
 * {@link AIBriefService} 用 Mockito mock，{@link GlobalExceptionHandler} 作 ControllerAdvice。
 * 认证上下文在 @BeforeEach 经 {@link UserContext#set} 模拟（生产由 JwtAuthFilter 写入），@AfterEach 清空。
 */
class AIBriefControllerTest {

    private MockMvc mockMvc;
    private AIBriefService aiBriefService;

    private final BriefContent sampleContent =
            new BriefContent("摘要", List.of(), "利好", "理由", "关注", List.of(), "AI 生成，非投资建议");

    @BeforeEach
    void setUp() {
        aiBriefService = mock(AIBriefService.class);
        AIBriefController controller = new AIBriefController(aiBriefService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        UserContext.set(new UserContext.Principal(1L, "alice"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- POST 创建（异步受理） ----

    @Test
    void create_returns202WithTaskId() throws Exception {
        // Arrange
        when(aiBriefService.createBrief(anyLong(), any(BriefType.class))).thenReturn(1L);

        // Act + Assert：202 Accepted + {taskId}
        mockMvc.perform(
                        post("/api/v1/ai-briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "100:1:20260921")
                                .content("{\"subjectId\":100,\"briefType\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(1));
        verify(aiBriefService).createBrief(100L, BriefType.STOCK);
    }

    @Test
    void create_quotaExhausted_returns429AndCode30030() throws Exception {
        // Arrange：成本上限触发
        when(aiBriefService.createBrief(anyLong(), any(BriefType.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "配额用尽"));

        // Act + Assert：429/30030
        mockMvc.perform(
                        post("/api/v1/ai-briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100,\"briefType\":1}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(30030));
    }

    @Test
    void create_subjectNotFound_returns404AndCode30001() throws Exception {
        // Arrange：个股型标的不存在
        when(aiBriefService.createBrief(anyLong(), any(BriefType.class)))
                .thenThrow(new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));

        // Act + Assert：404/30001
        mockMvc.perform(
                        post("/api/v1/ai-briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":999,\"briefType\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30001));
    }

    @Test
    void create_blankBriefType_returns400AndCode2001() throws Exception {
        // Arrange + Act + Assert：@NotNull 触发 → 2xxx
        mockMvc.perform(
                        post("/api/v1/ai-briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100,\"briefType\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(aiBriefService, never()).createBrief(anyLong(), any(BriefType.class));
    }

    @Test
    void create_briefTypeOutOfRange_returns400() throws Exception {
        // Arrange + Act + Assert：@Max(4) 触发 → 2xxx
        mockMvc.perform(
                        post("/api/v1/ai-briefs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectId\":100,\"briefType\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        verify(aiBriefService, never()).createBrief(anyLong(), any(BriefType.class));
    }

    // ---- GET 查询 ----

    @Test
    void get_returns200WithStatusContentSourceLinksDisclaimer() throws Exception {
        // Arrange：已完成
        AIBriefView view = new AIBriefView(1, sampleContent, List.of("http://f"), "AI 生成，非投资建议");
        when(aiBriefService.getBrief(1L)).thenReturn(view);

        // Act + Assert：200 + status/content/sourceLinks/disclaimer
        mockMvc.perform(get("/api/v1/ai-briefs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.content.summary").value("摘要"))
                .andExpect(jsonPath("$.data.sourceLinks[0]").value("http://f"))
                .andExpect(jsonPath("$.data.disclaimer").value("AI 生成，非投资建议"));
    }

    @Test
    void get_pending_returns200WithNullContent() throws Exception {
        // Arrange：处理中（无可展示内容）
        when(aiBriefService.getBrief(2L))
                .thenReturn(new AIBriefView(0, null, null, BriefContent.DEFAULT_DISCLAIMER));

        // Act + Assert
        mockMvc.perform(get("/api/v1/ai-briefs/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.disclaimer").value("AI 生成，非投资建议"));
    }

    @Test
    void get_notFound_returns404AndCode30032() throws Exception {
        // Arrange
        when(aiBriefService.getBrief(999L))
                .thenThrow(new BusinessException(ErrorCode.AI_BRIEF_NOT_FOUND));

        // Act + Assert：30032/404
        mockMvc.perform(get("/api/v1/ai-briefs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30032));
    }
}
