package com.info.platform.interfaces.aggregation;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.aggregation.AnnouncementPageView;
import com.info.platform.application.aggregation.EventPageView;
import com.info.platform.application.aggregation.SubjectSectionPageService;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SubjectSectionPageController 切片测试（M12 T90）：公告分区子端点 400 参数矩阵（page 缺席/&lt;1/&gt;5 上限引导语 / size
 * 越界拒绝不截断）+ 200 契约结构 + 404 标的不存在。
 *
 * <p>用 {@code MockMvcBuilders#standaloneSetup} 独立装配（不加载 Spring 上下文），Service 用 Mockito 替身（同 {@code
 * SubjectControllerTest} 模式）；page/size 校验在 Controller 经 {@code PageQuery} 共用件完成（口径与 M9 一致）。
 */
class SubjectSectionPageControllerTest {

    private MockMvc mockMvc;
    private SubjectSectionPageService sectionPageService;

    @BeforeEach
    void setUp() {
        sectionPageService = mock(SubjectSectionPageService.class);
        SubjectSectionPageController controller =
                new SubjectSectionPageController(sectionPageService);
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // ---- 200 契约结构 ----

    @Test
    void announcements_returns200WithPageContract() throws Exception {
        when(sectionPageService.announcements(Mockito.eq(1L), Mockito.eq(2), isNull()))
                .thenReturn(
                        new AnnouncementPageView(
                                List.of(
                                        Map.of(
                                                "title", "贵州茅台:2026年半年度报告",
                                                "publishedAt", "2026-08-15",
                                                "category", "其他",
                                                "url", "https://pdf.dfcfw.com/pdf/H2_AN_1.pdf")),
                                2,
                                10,
                                1074L,
                                true,
                                "https://data.eastmoney.com/notices/stock/600519.html",
                                "ok",
                                "东方财富公告"));

        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].title").value("贵州茅台:2026年半年度报告"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1074))
                .andExpect(jsonPath("$.data.paginationSupported").value(true))
                .andExpect(
                        jsonPath("$.data.moreUrl")
                                .value("https://data.eastmoney.com/notices/stock/600519.html"))
                .andExpect(jsonPath("$.data.sourceStatus").value("ok"))
                .andExpect(jsonPath("$.data.source").value("东方财富公告"));
    }

    @Test
    void announcements_sizeExplicitPassedThrough() throws Exception {
        when(sectionPageService.announcements(1L, 1, 25))
                .thenReturn(
                        new AnnouncementPageView(
                                List.of(), 1, 25, 0L, true, null, "ok", "东方财富公告"));

        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=1&size=25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(25));
    }

    // ---- 400 参数矩阵（page 必填 + 公告 5 页上限 + size 1~50） ----

    @Test
    void announcements_missingPage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/announcements"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("page 不能为空"));
    }

    @Test
    void announcements_pageBelowOne_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("page 至少为 1"));
    }

    @Test
    void announcements_pageOverFive_returns400WithSourceHint() throws Exception {
        // D5：后端 400 硬拒绝是权威（外呼封顶成立），msg 引导走源站
        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("page 超过上限 5，更多历史公告请走源站"));
    }

    @Test
    void announcements_pageAtUpperBound_isAccepted() throws Exception {
        when(sectionPageService.announcements(1L, 5, null))
                .thenReturn(
                        new AnnouncementPageView(
                                List.of(), 5, 10, null, true, null, "ok", "东方财富公告"));

        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(5));
    }

    @Test
    void announcements_sizeBelowOne_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=1&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("size 至少为 1"));
    }

    @Test
    void announcements_sizeOverFifty_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/announcements?page=1&size=51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("size 超过上限 50"));
    }

    // ---- M12 T91：事件分区分页 ----

    @Test
    void events_returns200WithPageContract() throws Exception {
        when(sectionPageService.events(Mockito.eq(1L), Mockito.eq(2), isNull()))
                .thenReturn(
                        new EventPageView(
                                List.of(
                                        Map.of(
                                                "anomalyType", "PRICE_CHANGE",
                                                "changePct", 5.2,
                                                "currentPrice", 1680.0,
                                                "triggerTime", "2026-09-20T02:00:00Z",
                                                "detail", "日涨跌幅 5.2% 触发阈值")),
                                2,
                                10,
                                37L,
                                "ok",
                                "事件监控"));

        mockMvc.perform(get("/api/v1/subjects/1/events?page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].anomalyType").value("PRICE_CHANGE"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(37))
                .andExpect(jsonPath("$.data.sourceStatus").value("ok"))
                .andExpect(jsonPath("$.data.source").value("事件监控"));
    }

    @Test
    void events_missingPage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("page 不能为空"));
    }

    @Test
    void events_pageBelowOne_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/events?page=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("page 至少为 1"));
    }

    @Test
    void events_sizeOverFifty_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/1/events?page=1&size=51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("size 超过上限 50"));
    }

    @Test
    void events_pageHasNoUpperBoundFive_likeAnnounce() throws Exception {
        // 事件无 5 页上限（本地数据翻完即止）：page=50 合法（PageQuery MAX_PAGE 口径内）
        when(sectionPageService.events(1L, 50, null))
                .thenReturn(new EventPageView(List.of(), 50, 10, 0L, "missing", "事件监控"));

        mockMvc.perform(get("/api/v1/subjects/1/events?page=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(50))
                .andExpect(jsonPath("$.data.sourceStatus").value("missing"));
    }

    // ---- 404 ----

    @Test
    void announcements_subjectNotFound_returns404AndCode30001() throws Exception {
        when(sectionPageService.announcements(999L, 1, null))
                .thenThrow(new BusinessException(ErrorCode.SUBJECT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/subjects/999/announcements?page=1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30001));
    }
}
