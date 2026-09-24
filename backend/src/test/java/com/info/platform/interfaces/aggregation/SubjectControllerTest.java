package com.info.platform.interfaces.aggregation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.info.platform.application.aggregation.AggregationService;
import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.application.aggregation.SubjectQuote;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.interfaces.common.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * SubjectController 切片测试（T09）：GET detail 200 + sourceStatus 结构 / 200 + 列表分区 / 404 标的不存在 / 400 非法
 * section / 空 sections 默认全部分区；by-code 代码解析（P0-1）。
 *
 * <p>用 {@link MockMvcBuilders#standaloneSetup} 独立装配 MockMvc（不加载 Spring 上下文，避免 @MapperScan 触发
 * MyBatis mapper bean 初始化），手动注入 Controller + {@link GlobalExceptionHandler} 作为 ControllerAdvice。
 * AggregationService 用 Mockito mock。
 */
class SubjectControllerTest {

    private MockMvc mockMvc;
    private AggregationService aggregationService;
    private SubjectRepository subjectRepository;

    @BeforeEach
    void setUp() {
        aggregationService = mock(AggregationService.class);
        subjectRepository = mock(SubjectRepository.class);
        SubjectController controller = new SubjectController(aggregationService, subjectRepository);
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
    void getDetail_returns200WithEventsList() throws Exception {
        // T08（ADR-0013）：事件分区（本地 anomaly_event 近期异动）以 events 数组承载，sourceStatus.event 三态之一
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                Map.of(
                                        "anomalyType",
                                        "PRICE_CHANGE",
                                        "changePct",
                                        new BigDecimal("3.25"),
                                        "triggerTime",
                                        "2026-09-21T02:00:00Z",
                                        "detail",
                                        "日涨跌幅 3.25% 触发阈值 3.0%")),
                        Map.of("event", "ok"));
        when(aggregationService.getDetail(eq(1L), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/subjects/1/detail?sections=event"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[0].anomalyType").value("PRICE_CHANGE"))
                .andExpect(jsonPath("$.data.events[0].changePct").value(3.25))
                .andExpect(jsonPath("$.data.events[0].triggerTime").value("2026-09-21T02:00:00Z"))
                .andExpect(jsonPath("$.data.sourceStatus.event").value("ok"));
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
                        null,
                        Map.of("quote", "missing"));
        when(aggregationService.getDetail(eq(1L), any())).thenReturn(detail);

        mockMvc.perform(get("/api/v1/subjects/1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---- by-code 代码解析（P0-1）：前端路由代码 → 数字主键 ----

    /** 构造已落库标的实体（reconstruct 绕过 Builder 预留类型守卫，模拟仓储回读）。 */
    private static Subject persistedSubject(Long id, String code) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of(),
                "白酒",
                SubjectStatus.ENABLED,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void getByCode_returns200WithNumericIdAndSummary() throws Exception {
        when(subjectRepository.findByCode(SubjectCode.of("SH600519")))
                .thenReturn(Optional.of(persistedSubject(1L, "SH600519")));

        mockMvc.perform(get("/api/v1/subjects/by-code/SH600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data.name").value("贵州茅台"))
                .andExpect(jsonPath("$.data.market").value("A_SHARE"))
                .andExpect(jsonPath("$.data.type").value(1))
                .andExpect(jsonPath("$.data.industry").value("白酒"));
    }

    @Test
    void getByCode_unknownCode_returns404AndCode30001() throws Exception {
        when(subjectRepository.findByCode(SubjectCode.of("SH999999"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/subjects/by-code/SH999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(30001))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getByCode_blankCode_returns400AndCode2001() throws Exception {
        // 模板变量传入空白串（等价真实容器 %20 解码后到达控制器）：守卫在领域值对象抛 IllegalArgumentException（→500）之前
        mockMvc.perform(get("/api/v1/subjects/by-code/{code}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ---- search 标的搜索（体检 P1-2：搜索选择器数据源） ----

    @Test
    void search_returnsMatchingSubjectsWithSummaryFields() throws Exception {
        when(subjectRepository.searchEnabled("茅台", 20))
                .thenReturn(List.of(persistedSubject(1L, "SH600519")));

        mockMvc.perform(get("/api/v1/subjects/search").param("q", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.data[0].market").value("A_SHARE"))
                .andExpect(jsonPath("$.data[0].type").value(1))
                .andExpect(jsonPath("$.data[0].industry").value("白酒"));
    }

    @Test
    void search_trimsKeyword_andPassesLimitThrough() throws Exception {
        when(subjectRepository.searchEnabled("银行", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/subjects/search").param("q", " 银行 ").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(subjectRepository).searchEnabled("银行", 5);
    }

    @Test
    void search_noMatch_returns200WithEmptyList() throws Exception {
        when(subjectRepository.searchEnabled("xyz不存在的标的", 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/subjects/search").param("q", "xyz不存在的标的"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void search_blankQ_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/search").param("q", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void search_missingQ_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void search_limitOutOfRange_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/search").param("q", "茅台").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(get("/api/v1/subjects/search").param("q", "茅台").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ---- quotes 批量标的摘要+行情（体检 P1-2 自选清单表格） ----

    @Test
    void getQuotes_returns200WithSummaryAndQuote() throws Exception {
        when(aggregationService.getQuotes(List.of(1L)))
                .thenReturn(
                        List.of(
                                new SubjectQuote(
                                        1L,
                                        "SH600519",
                                        "贵州茅台",
                                        "A_SHARE",
                                        1,
                                        "白酒",
                                        Map.of("price", new BigDecimal("1680.50"), "changePct", new BigDecimal("1.25")))));

        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].subjectCode").value("SH600519"))
                .andExpect(jsonPath("$.data[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.data[0].quote.price").value(1680.50))
                .andExpect(jsonPath("$.data[0].quote.changePct").value(1.25));
    }

    @Test
    void getQuotes_degradedRow_keepsSummaryWithNullQuote() throws Exception {
        when(aggregationService.getQuotes(List.of(2L)))
                .thenReturn(
                        List.of(new SubjectQuote(2L, "SZ300750", "宁德时代", "A_SHARE", 1, "动力电池", null)));

        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subjectCode").value("SZ300750"))
                .andExpect(jsonPath("$.data[0].quote").doesNotExist());
    }

    @Test
    void getQuotes_blankIds_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void getQuotes_missingIds_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/quotes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void getQuotes_nonNumericOrNonPositiveId_returns400AndCode2001() throws Exception {
        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", "1,abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void getQuotes_tooManyIds_returns400AndCode2001() throws Exception {
        String ids = java.util.stream.LongStream.rangeClosed(1, 51)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/api/v1/subjects/quotes").param("ids", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2001));
    }
}
