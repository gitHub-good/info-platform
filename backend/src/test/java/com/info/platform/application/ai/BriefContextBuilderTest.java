package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.ai.BriefType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BriefContextBuilder 单测（T21）：聚合数据 → 提示词上下文 Map 投影，AAA 结构。
 *
 * <p>覆盖：全分区映射（含 gross_margin→grossProfitMargin 键映射、preClose 回退 close、netProfitYoy/ps=暂无） / null 详情
 * / 缺分区写暂无 / 空公告新闻写暂无。
 */
class BriefContextBuilderTest {

    private final BriefContextBuilder builder = new BriefContextBuilder();

    @Test
    void build_fullDetail_mapsAllKeys() {
        // Arrange：六分区齐全
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        Map.of("price", 1680.0, "changePct", 1.2, "preClose", 1660.0),
                        Map.of(
                                "reportDate", "2025-12-31",
                                "revenue", "127560000000.00",
                                "netProfit", "86280000000.00",
                                "grossProfitMargin", "91.5",
                                "roe", "30.55"),
                        Map.of("peTtm", 25.0, "pb", 9.5),
                        List.of(
                                Map.of(
                                        "title",
                                        "年报",
                                        "publishedAt",
                                        "2026-03-28",
                                        "url",
                                        "http://a")),
                        List.of(
                                Map.of(
                                        "title",
                                        "茅台涨价",
                                        "publishedAt",
                                        "2026-03-20",
                                        "url",
                                        "http://n")),
                        List.of(),
                        Map.of());

        // Act
        Map<String, String> ctx = builder.build(detail, BriefType.STOCK);

        // Assert：标的/行情/财务/估值/公告/新闻键齐全；源不产的 netProfitYoy/ps=暂无
        assertThat(ctx.get("subjectName")).isEqualTo("贵州茅台");
        assertThat(ctx.get("subjectCode")).isEqualTo("SH600519");
        assertThat(ctx.get("industry")).isEqualTo("白酒");
        assertThat(ctx.get("price")).isEqualTo("1680.0");
        assertThat(ctx.get("changePct")).isEqualTo("1.2");
        assertThat(ctx.get("preClose")).isEqualTo("1660.0");
        assertThat(ctx.get("reportDate")).isEqualTo("2025-12-31");
        assertThat(ctx.get("revenue")).isEqualTo("127560000000.00");
        assertThat(ctx.get("netProfit")).isEqualTo("86280000000.00");
        assertThat(ctx.get("grossMargin")).isEqualTo("91.5"); // gross_margin→grossProfitMargin
        assertThat(ctx.get("roe")).isEqualTo("30.55");
        assertThat(ctx.get("peTtm")).isEqualTo("25.0");
        assertThat(ctx.get("pb")).isEqualTo("9.5");
        assertThat(ctx.get("netProfitYoy")).isEqualTo("暂无");
        assertThat(ctx.get("ps")).isEqualTo("暂无");
        assertThat(ctx.get("announcementsList")).contains("年报").contains("http://a");
        assertThat(ctx.get("newsList")).contains("茅台涨价").contains("http://n");
    }

    @Test
    void build_nullDetail_returnsEmptyContext() {
        // Act + Assert：无详情→空上下文（占位符全保留，由 PromptTemplateService 处理）
        assertThat(builder.build(null, BriefType.STOCK)).isEmpty();
    }

    @Test
    void build_missingSections_writesNa() {
        // Arrange：quote/finance/valuation 全 null
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, null),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of());

        // Act
        Map<String, String> ctx = builder.build(detail, BriefType.STOCK);

        // Assert：缺源数据写「暂无」而非保留 {{key}}
        assertThat(ctx.get("price")).isEqualTo("暂无");
        assertThat(ctx.get("roe")).isEqualTo("暂无");
        assertThat(ctx.get("peTtm")).isEqualTo("暂无");
        assertThat(ctx.get("industry")).isEqualTo("暂无");
    }

    @Test
    void build_preCloseFallsBackToClose() {
        // Arrange：quote 无 preClose 但有 close
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "茅台", "A_SHARE", 1, "白酒"),
                        Map.of("price", 1680.0, "close", 1660.0),
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of());

        // Act + Assert：preClose 回退 close
        Map<String, String> ctx = builder.build(detail, BriefType.STOCK);
        assertThat(ctx.get("preClose")).isEqualTo("1660.0");
    }

    @Test
    void build_emptyItems_writesNa() {
        // Arrange：公告/新闻空数组
        SubjectDetail detail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "茅台", "A_SHARE", 1, "白酒"),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        Map.of());

        // Act + Assert
        Map<String, String> ctx = builder.build(detail, BriefType.STOCK);
        assertThat(ctx.get("announcementsList")).isEqualTo("暂无");
        assertThat(ctx.get("newsList")).isEqualTo("暂无");
    }
}
