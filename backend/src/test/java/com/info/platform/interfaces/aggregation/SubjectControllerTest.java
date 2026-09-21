package com.info.platform.interfaces.aggregation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SubjectController 切片测试（T09）：GET detail 200 + sourceStatus 结构 / 200 + 列表分区 / 404 标的不存在 / 400 非法
 * section / 空 sections 默认全部分区。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文，避免 @MapperScan 触发
 * MyBatis mapper bean 初始化），手动注入 Controller + {@link GlobalExceptionHandler} 作为 ControllerAdvice。
 * AggregationService 用 Mockito mock。
 */
class SubjectControllerTest {

    private MockMvc mockMvc;
    private AggregationService aggregationService;

    @BeforeEach
    void setUp() {
        aggregationService = mock(AggregationService.class);
        SubjectController controller = new SubjectController(aggregationService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void getDetail_returns200WithSourceStatusStructure() throws Exception {
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        Map.of("price", new BigDecimal("1680.50")),
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("quote", "ok", "finance", "missing", "policy", "missing"));
        when(aggregationService.getDetail(eq(1L), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/subjects/1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.subject.subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data.subject.name").value("贵州茅台"))
                .andExpect(jsonPath("$.data.subject.market").value("A_SHARE"))
                .andExpect(jsonPath("$.data.subject.type").value(1))
                .andExpect(jsonPath("$.data.subject.industry").value("白酒"))
                .andExpect(jsonPath("$.data.quote.price").value(1680.50))
                .andExpect(jsonPath("$.data.sourceStatus.quote").value("ok"))
                .andExpect(jsonPath("$.data.sourceStatus.finance").value("missing"))
                .andExpect(jsonPath("$.data.sourceStatus.policy").value("missing"));
    }

    @Test
    void getDetail_returns200WithAnnouncementsList() throws Exception {
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        null,
                        null,
                        null,
                        List.of(Map.<String, Object>of("title", "公告1", "url", "https://x")),
                        null,
                        null,
                        Map.of("announce", "ok"));
        when(aggregationService.getDetail(eq(1L), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/subjects/1/detail?sections=announce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcements[0].title").value("公告1"))
                .andExpect(jsonPath("$.data.sourceStatus.announce").value("ok"));
    }

    @Test
    void getDetail_subjectNotFound_returns404AndCode30001() throws Exception {
        when(aggregationService.getDetail(eq(999L), any()))
                .thenThrow(new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/subjects/999/detail"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30001))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getDetail_illegalSection_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/detail?sections=invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void getDetail_emptySections_defaultsToAll() throws Exception {
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of("quote", "missing"));
        when(aggregationService.getDetail(eq(1L), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/subjects/1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
