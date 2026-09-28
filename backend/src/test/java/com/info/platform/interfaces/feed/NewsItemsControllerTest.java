package com.info.platform.interfaces.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.feed.MatchedSubjectView;
import com.info.platform.application.feed.NewsItemView;
import com.info.platform.application.feed.NewsItemsCursorView;
import com.info.platform.application.feed.NewsItemsPagedView;
import com.info.platform.application.feed.NewsItemsQueryService;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.feed.FeedItemRepository.LibraryFilter;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * NewsItemsController 切片测试（T104，方案 §4.5 + M9 双模式分派；T160 增 q/l0/l1 过滤与 analysis 字段）：
 * 游标模式（beforeId/limit 默认 20 ≤50）与页码模式（page/size/total）互斥校验 + sourceId 过滤透传 + 三过滤参数校验/缺省/透传 +
 * 响应增量字段。 standalone MockMvc，service mock。
 */
class NewsItemsControllerTest {

    private MockMvc mockMvc;
    private NewsItemsQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = mock(NewsItemsQueryService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new NewsItemsController(queryService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private static NewsItemView view(long id, String title) {
        return analysisView(id, title, "PASS", null, null, null, false, null, null, List.of());
    }

    /** 带 analysis 字段的条目视图（T160 增量字段断言用）。 */
    private static NewsItemView analysisView(
            long id,
            String title,
            String l0Result,
            String l0Detail,
            String l1Main,
            Double l1Confidence,
            boolean lowConfidence,
            Long nearDupMasterId,
            String nearDupMasterUrl) {
        return analysisView(
                id,
                title,
                l0Result,
                l0Detail,
                l1Main,
                l1Confidence,
                lowConfidence,
                nearDupMasterId,
                nearDupMasterUrl,
                List.of());
    }

    /** 带回联标的的条目视图（V3.1 matchedSubjects 断言用）。 */
    private static NewsItemView analysisView(
            long id,
            String title,
            String l0Result,
            String l0Detail,
            String l1Main,
            Double l1Confidence,
            boolean lowConfidence,
            Long nearDupMasterId,
            String nearDupMasterUrl,
            List<MatchedSubjectView> matchedSubjects) {
        return new NewsItemView(
                id,
                7L,
                "t104_src",
                "测试源",
                title,
                "摘要",
                "https://example.com/n",
                "作者",
                "2026-09-22T01:31:00Z",
                "2026-09-22T01:31:30Z",
                l0Result,
                l0Detail,
                l1Main,
                l1Confidence,
                lowConfidence,
                nearDupMasterId,
                nearDupMasterUrl,
                matchedSubjects);
    }

    @Test
    void list_cursorMode_returnsItemsAndNextBeforeId() throws Exception {
        when(queryService.listCursor(eq(null), eq(null), eq(20)))
                .thenReturn(new NewsItemsCursorView(List.of(view(2, "b"), view(1, "a")), 1L));

        mockMvc.perform(get("/api/v1/news-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("b"))
                .andExpect(jsonPath("$.data.items[0].sourceCode").value("t104_src"))
                .andExpect(jsonPath("$.data.nextBeforeId").value(1));
    }

    @Test
    void list_cursorMode_passesSourceIdAndBeforeIdAndLimit() throws Exception {
        when(queryService.listCursor(eq(7L), eq(100L), eq(5)))
                .thenReturn(new NewsItemsCursorView(List.of(view(99, "x")), null));

        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("sourceId", "7")
                                .param("beforeId", "100")
                                .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.nextBeforeId").doesNotExist());
    }

    @Test
    void list_cursorMode_returnsAnalysisFields_too() throws Exception {
        // 游标路径同样带 analysis 增量字段（追加式，既有消费方零破坏）；且不受 l0 过滤影响（页码模式专属）
        when(queryService.listCursor(eq(null), eq(null), eq(20)))
                .thenReturn(
                        new NewsItemsCursorView(
                                List.of(
                                        analysisView(
                                                5,
                                                "近重复条",
                                                "NEAR_DUP",
                                                "hamming=2;edit=0.18",
                                                null,
                                                null,
                                                false,
                                                4L,
                                                "https://example.com/master")),
                                null));

        mockMvc.perform(get("/api/v1/news-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].l0Result").value("NEAR_DUP"))
                .andExpect(jsonPath("$.data.items[0].l0Detail").value("hamming=2;edit=0.18"))
                .andExpect(jsonPath("$.data.items[0].l1Main").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].nearDupMasterId").value(4))
                .andExpect(
                        jsonPath("$.data.items[0].nearDupMasterUrl")
                                .value("https://example.com/master"));
    }

    @Test
    void list_limitOver50_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_limitBelow1_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_pageMode_returnsTotalPageSize() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(2), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(3, "c")), 41L, 2, 20));

        mockMvc.perform(get("/api/v1/news-items").param("sourceId", "7").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("c"))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void list_pageWithBeforeId_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("page", "2").param("beforeId", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_sizeWithoutPage_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_limitWithPage_rejected400() throws Exception {
        // limit 为游标模式专属参数（契约确定性优先于宽容，M9 同口径）
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("limit", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_invalidSourceId_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("sourceId", "abc"))
                .andExpect(status().isBadRequest());
    }

    // —— T160（M19 V2.1，REQ-20260926-16 拍板一）：页码模式增量过滤参数 q/l0/l1 + analysis join 字段 ——

    @Test
    void list_invalidL0_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("l0", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_l1NotIn35Enums_rejected400() throws Exception {
        // 「白酒」非 35 主分类枚举（申万二级），须 400 拒绝
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("l1", "白酒"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_qTooShort_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_qTooLong_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("q", "字".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_filterParamWithoutPage_rejected400() throws Exception {
        // q/l0/l1 为页码模式专属过滤参数（M9 契约确定性先例：出现而 page 缺席 → 400）
        mockMvc.perform(get("/api/v1/news-items").param("l0", "NOISE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_filterParamBlank_treatedAsAbsent() throws Exception {
        // blank 视为缺席（M9 keyword/status 同惯例）：空 q/l1 不触发「缺 page」也不参与过滤
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(3, "c")), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/news-items").param("l0", "").param("page", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void list_pageMode_responseIncludesAnalysisFields() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(
                        new NewsItemsPagedView(
                                List.of(
                                        analysisView(
                                                3, "c", "NOISE", "推广", "电子", 0.42, true, null,
                                                null)),
                                1L,
                                1,
                                20));

        mockMvc.perform(get("/api/v1/news-items").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].l0Result").value("NOISE"))
                .andExpect(jsonPath("$.data.items[0].l0Detail").value("推广"))
                .andExpect(jsonPath("$.data.items[0].l1Main").value("电子"))
                .andExpect(jsonPath("$.data.items[0].l1Confidence").value(0.42))
                .andExpect(jsonPath("$.data.items[0].lowConfidence").value(true))
                .andExpect(jsonPath("$.data.items[0].nearDupMasterId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].nearDupMasterUrl").doesNotExist());
    }

    @Test
    void list_pageMode_defaultsL0ToPass() throws Exception {
        // 缺省 PASS（REQ 拍板一 API 缺口表冻结值；兼容评估见控制器 javadoc 留档）
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(1, "a")), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/news-items").param("page", "1")).andExpect(status().isOk());

        ArgumentCaptor<LibraryFilter> captor = ArgumentCaptor.forClass(LibraryFilter.class);
        verify(queryService).listPaged(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().l0()).isEqualTo(L0Result.PASS);
        assertThat(captor.getValue().keyword()).isNull();
        assertThat(captor.getValue().mainCategory()).isNull();
    }

    @Test
    void list_pageMode_l0All_meansNoL0Filter() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(1, "a")), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("l0", "ALL"))
                .andExpect(status().isOk());

        ArgumentCaptor<LibraryFilter> captor = ArgumentCaptor.forClass(LibraryFilter.class);
        verify(queryService).listPaged(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().l0()).isNull();
    }

    // —— T210（M24 V2.4）：fetchedFrom/To 入库时间窗（页码模式专属，REQ-20260928-20 拍板三对账前提） ——

    @Test
    void list_pageMode_passesFetchedWindowThrough() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(1, "a")), 1L, 1, 20));

        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("fetchedFrom", "2026-09-22")
                                .param("fetchedTo", "2026-09-22"))
                .andExpect(status().isOk());

        ArgumentCaptor<LibraryFilter> captor = ArgumentCaptor.forClass(LibraryFilter.class);
        verify(queryService).listPaged(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().fetchedFrom()).isEqualTo("2026-09-22");
        assertThat(captor.getValue().fetchedTo()).isEqualTo("2026-09-22");
        assertThat(captor.getValue().publishedFrom()).isNull();
    }

    @Test
    void list_fetchedWindowInvalidDate_rejected400() throws Exception {
        // 非 yyyy-MM-dd / 不存在的日期 → 400 字段级（沿 publishedFrom/To 校验先例）
        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("fetchedFrom", "2026-9-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("fetchedTo", "2026-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_fetchedFromAfterFetchedTo_rejected400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("fetchedFrom", "2026-09-23")
                                .param("fetchedTo", "2026-09-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_fetchedFromWithoutPage_rejected400() throws Exception {
        // 页码模式专属参数（M9 契约确定性先例）：出现而 page 缺席 → 400
        mockMvc.perform(get("/api/v1/news-items").param("fetchedFrom", "2026-09-22"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_pageMode_passesSourceIdQAndL1Through() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(1, "a")), 1L, 1, 20));

        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("sourceId", "7")
                                .param("q", "  降息  ")
                                .param("l0", "NEAR_DUP")
                                .param("l1", "银行"))
                .andExpect(status().isOk());

        ArgumentCaptor<LibraryFilter> captor = ArgumentCaptor.forClass(LibraryFilter.class);
        verify(queryService).listPaged(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().sourceId()).isEqualTo(7L);
        assertThat(captor.getValue().keyword()).isEqualTo("降息"); // trim 后透传
        assertThat(captor.getValue().l0()).isEqualTo(L0Result.NEAR_DUP);
        assertThat(captor.getValue().mainCategory()).isEqualTo("银行");
    }

    // —— V3.1 资讯库标的增强：subjectCode 参数（页码模式专属）+ matchedSubjects 响应字段 ——

    @Test
    void list_pageMode_passesSubjectCodeThrough() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(new NewsItemsPagedView(List.of(view(1, "a")), 1L, 1, 20));

        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("subjectCode", " SH600519 "))
                .andExpect(status().isOk());

        ArgumentCaptor<LibraryFilter> captor = ArgumentCaptor.forClass(LibraryFilter.class);
        verify(queryService).listPaged(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().subjectCode()).isEqualTo("SH600519"); // trim 后透传
    }

    @Test
    void list_subjectCodeWithoutPage_rejected400() throws Exception {
        // 页码模式专属参数（M9 契约确定性先例）：出现而 page 缺席 → 400
        mockMvc.perform(get("/api/v1/news-items").param("subjectCode", "SH600519"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_subjectCodeBlank_treatedAsAbsent() throws Exception {
        // blank 视为缺席：不触发「缺 page」400，也不参与过滤
        when(queryService.listCursor(eq(null), eq(null), eq(20)))
                .thenReturn(new NewsItemsCursorView(List.of(view(1, "a")), null));

        mockMvc.perform(get("/api/v1/news-items").param("subjectCode", "  "))
                .andExpect(status().isOk());
    }

    @Test
    void list_invalidSubjectCode_rejected400() throws Exception {
        // 标的代码值域 [A-Za-z0-9]（LIKE 通配面防护：%/ 下划线按字面拒绝）
        mockMvc.perform(
                        get("/api/v1/news-items")
                                .param("page", "1")
                                .param("subjectCode", "SH_600519"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("subjectCode", "%"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(get("/api/v1/news-items").param("page", "1").param("subjectCode", "A"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void list_pageMode_responseIncludesMatchedSubjects() throws Exception {
        when(queryService.listPaged(any(LibraryFilter.class), eq(1), eq(20)))
                .thenReturn(
                        new NewsItemsPagedView(
                                List.of(
                                        analysisView(
                                                3,
                                                "茅台条",
                                                "PASS",
                                                null,
                                                "食品饮料",
                                                0.9,
                                                false,
                                                null,
                                                null,
                                                List.of(
                                                        new MatchedSubjectView(
                                                                "SH600519", "贵州茅台", "白酒"),
                                                        new MatchedSubjectView(
                                                                "SZ300024", "机器人", null))),
                                        analysisView(
                                                4, "无标的条", "PASS", null, null, null, false, null,
                                                null, List.of())),
                                2L,
                                1,
                                20));

        mockMvc.perform(get("/api/v1/news-items").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].matchedSubjects.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].matchedSubjects[0].code").value("SH600519"))
                .andExpect(jsonPath("$.data.items[0].matchedSubjects[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.data.items[0].matchedSubjects[0].industry").value("白酒"))
                .andExpect(jsonPath("$.data.items[0].matchedSubjects[1].industry").doesNotExist())
                // 无标的行：空数组（非 null——前端零判空）
                .andExpect(jsonPath("$.data.items[1].matchedSubjects.length()").value(0));
    }
}
