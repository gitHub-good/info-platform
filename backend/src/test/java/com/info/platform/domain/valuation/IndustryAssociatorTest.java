package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * IndustryAssociator 行业关联派生单测（T170，ADR-0058 裁决 2 —— 绕行 subject_master.industry 的双路派生）： 路 A 事件
 * subjects 回联 / 路 B 资讯 main+sub 双权 / 窗口 30 天消退 / 融合取最强最近 / 容器 main 不记 / 空 code 跳过。
 */
class IndustryAssociatorTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    private static final int WINDOW = 30;

    private IndustryAssociator.EventLink eventLink(
            List<String> codes, List<String> industries, int ageDays) {
        return new IndustryAssociator.EventLink(codes, industries, SNAPSHOT.minusDays(ageDays));
    }

    private IndustryAssociator.NewsLink newsLink(
            List<String> codes, String main, String sub, int ageDays) {
        return new IndustryAssociator.NewsLink(codes, main, sub, SNAPSHOT.minusDays(ageDays));
    }

    @Test
    void pathA_eventSubjects_linkedToAffectedIndustries() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料", "银行"), 3)),
                        List.of(),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc).containsKey("SH600519");
        List<IndustryAssociator.Association> links = assoc.get("SH600519");
        assertThat(links).hasSize(2);
        assertThat(links)
                .anySatisfy(
                        a -> {
                            assertThat(a.industry()).isEqualTo("食品饮料");
                            assertThat(a.weight()).isEqualTo(1.0);
                            assertThat(a.lastSeenAgeDays()).isEqualTo(3);
                            assertThat(a.source()).isEqualTo(IndustryAssociator.Source.EVENT);
                        });
    }

    @Test
    void pathB_newsMainAndSub_dualWeights() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(),
                        List.of(newsLink(List.of("SZ000001"), "银行", "房地产", 5)),
                        SNAPSHOT,
                        WINDOW);

        List<IndustryAssociator.Association> links = assoc.get("SZ000001");
        assertThat(links).hasSize(2);
        IndustryAssociator.Association main =
                links.stream().filter(a -> a.industry().equals("银行")).findFirst().orElseThrow();
        IndustryAssociator.Association sub =
                links.stream().filter(a -> a.industry().equals("房地产")).findFirst().orElseThrow();
        assertThat(main.weight()).isEqualTo(1.0);
        assertThat(main.source()).isEqualTo(IndustryAssociator.Source.NEWS_MAIN);
        assertThat(sub.weight()).isEqualTo(0.5); // 次关联半权（§3.2）
        assertThat(sub.source()).isEqualTo(IndustryAssociator.Source.NEWS_SUB);
        assertThat(main.lastSeenAgeDays()).isEqualTo(5);
    }

    @Test
    void containerMain_subOnlyRecorded() {
        // main=宏观（容器）不记；sub=申万 → 仅次关联
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(),
                        List.of(newsLink(List.of("SH600519"), "宏观", "电子", 1)),
                        SNAPSHOT,
                        WINDOW);

        List<IndustryAssociator.Association> links = assoc.get("SH600519");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).industry()).isEqualTo("电子");
        assertThat(links.get(0).weight()).isEqualTo(0.5);
    }

    @Test
    void windowFade_thirtyDays() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(
                                eventLink(List.of("SH600519"), List.of("食品饮料"), 29),
                                eventLink(List.of("SZ000001"), List.of("银行"), 30)),
                        List.of(),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc.get("SH600519")).hasSize(1); // age 29 在窗
        assertThat(assoc.get("SZ000001")).isNull(); // age 30 越窗消退
    }

    @Test
    void sameIndustryMerged_strongestWeightMostRecentKept() {
        // 事件路（w1.0 age10）+ 资讯 main（w1.0 age1）→ 保 w1.0 与最近 age1；对次关联（w0.5 age0）保 1.0 主关联
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 10)),
                        List.of(newsLink(List.of("SH600519"), "食品饮料", null, 1)),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc.get("SH600519")).hasSize(1);
        IndustryAssociator.Association merged = assoc.get("SH600519").get(0);
        assertThat(merged.weight()).isEqualTo(1.0);
        assertThat(merged.lastSeenAgeDays()).isEqualTo(1);
        assertThat(merged.source()).isEqualTo(IndustryAssociator.Source.NEWS_MAIN);
    }

    @Test
    void eventBeatsSubAssociation_onEqualRecency() {
        // 同 age：EVENT(1.0) 强于 NEWS_SUB(0.5) → 保 EVENT
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("电子"), 2)),
                        List.of(newsLink(List.of("SH600519"), "宏观", "电子", 2)),
                        SNAPSHOT,
                        WINDOW);

        IndustryAssociator.Association merged = assoc.get("SH600519").get(0);
        assertThat(merged.weight()).isEqualTo(1.0);
        assertThat(merged.source()).isEqualTo(IndustryAssociator.Source.EVENT);
    }

    @Test
    void blankSubjectCodes_skipped() {
        // 事件回联 code 可空（未回联仅留名）——不产生关联
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("", "  "), List.of("银行"), 0)),
                        List.of(newsLink(List.of(""), "银行", null, 0)),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc).isEmpty();
    }

    @Test
    void multipleSubjects_independentAssociationSets() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(
                                eventLink(List.of("SH600519", "SZ000001"), List.of("食品饮料"), 0),
                                eventLink(List.of("SZ300750"), List.of("电力设备"), 4)),
                        List.of(),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc.get("SH600519")).hasSize(1);
        assertThat(assoc.get("SZ000001")).hasSize(1);
        assertThat(assoc.get("SZ300750")).hasSize(1);
        assertThat(assoc.get("SZ300750").get(0).industry()).isEqualTo("电力设备");
    }

    @Test
    void noInputs_emptyMap_deterministic() {
        assertThat(IndustryAssociator.associate(List.of(), List.of(), SNAPSHOT, WINDOW)).isEmpty();
    }

    // ---- 路 C 行业成员回哺（M21 T180，ADR-0059 裁决 1 / 方案 §4.1.4） ----

    private static IndustryAssociator.MemberLink member(String code, String industry) {
        return new IndustryAssociator.MemberLink(code, industry);
    }

    @Test
    void pathC_memberEdge_weightPointThree_ageZero() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(),
                        List.of(),
                        List.of(member("SZ300024", "机械设备")),
                        SNAPSHOT,
                        WINDOW);

        List<IndustryAssociator.Association> links = assoc.get("SZ300024");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).industry()).isEqualTo("机械设备");
        assertThat(links.get(0).weight()).isEqualTo(0.3);
        assertThat(links.get(0).lastSeenAgeDays()).isZero();
        assertThat(links.get(0).source()).isEqualTo(IndustryAssociator.Source.INDUSTRY_MEMBER);
    }

    @Test
    void pathC_onlyFillsEmpty_neverDisplacesPathAOrB() {
        // 同 (标的, 行业) 已有路 A（1.0）/ 路 B 次（0.5）→ 路 C（0.3）不顶替（权重最大者优先，只补空）
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 10)),
                        List.of(newsLink(List.of("SH600519"), "宏观", "电子", 1)),
                        List.of(member("SH600519", "食品饮料"), member("SH600519", "电子")),
                        SNAPSHOT,
                        WINDOW);

        List<IndustryAssociator.Association> links = assoc.get("SH600519");
        assertThat(links).hasSize(2);
        IndustryAssociator.Association event =
                links.stream().filter(a -> a.industry().equals("食品饮料")).findFirst().orElseThrow();
        IndustryAssociator.Association sub =
                links.stream().filter(a -> a.industry().equals("电子")).findFirst().orElseThrow();
        assertThat(event.weight()).isEqualTo(1.0);
        assertThat(event.source()).isEqualTo(IndustryAssociator.Source.EVENT);
        assertThat(sub.weight()).isEqualTo(0.5);
        assertThat(sub.source()).isEqualTo(IndustryAssociator.Source.NEWS_SUB);
    }

    @Test
    void pathC_newIndustry_addsMemberEdgeAlongsidePathA() {
        // 路 A 行业与路 C 行业不同 → 两行并存（成员边扩 F2 覆盖面即设计目的）
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 5)),
                        List.of(),
                        List.of(member("SH600519", "食品饮料"), member("SH600519", "银行")),
                        SNAPSHOT,
                        WINDOW);

        List<IndustryAssociator.Association> links = assoc.get("SH600519");
        assertThat(links).hasSize(2);
        assertThat(links.get(0).industry()).isEqualTo("食品饮料"); // 1.0 在前
        assertThat(links.get(1).industry()).isEqualTo("银行"); // 0.3 成员边在后
        assertThat(links.get(1).source()).isEqualTo(IndustryAssociator.Source.INDUSTRY_MEMBER);
    }

    @Test
    void pathC_blankCodeOrNonSwIndustry_defensivelySkipped() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(),
                        List.of(),
                        List.of(
                                member("", "银行"),
                                member("  ", "银行"),
                                member("SH600000", null),
                                member("SH600000", "银行Ⅱ"),
                                member("SH600000", "宏观")),
                        SNAPSHOT,
                        WINDOW);

        assertThat(assoc).isEmpty(); // 空 code / 非申万行业（板块原文/容器）双保险跳过
    }

    @Test
    void pathC_dualArgOverload_equivalentToEmptyMembers() {
        // 兼容入口（M20 调用面）= 全量入口传空成员集
        Map<String, List<IndustryAssociator.Association>> viaOverload =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 3)),
                        List.of(),
                        SNAPSHOT,
                        WINDOW);
        Map<String, List<IndustryAssociator.Association>> viaFull =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 3)),
                        List.of(),
                        List.of(),
                        SNAPSHOT,
                        WINDOW);
        assertThat(viaOverload).isEqualTo(viaFull);
    }

    @Test
    void pathC_pureMemberF2_cappedAtThirty() {
        // ADR-0059 裁决 1④：纯成员标的 F2 = 100 × 0.3 × heatNorm ≤ 30（heatNorm 顶格 1.0 也恰 30.0）——
        // btConductionMin=50 不可仅凭成员达标（「突破需点名传导」语义守恒）
        java.util.List<HeatRow> heat =
                java.util.List.of(new HeatRow("机械设备", 99.0), new HeatRow("银行", 10.0));
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(),
                        List.of(),
                        List.of(member("SZ300024", "机械设备"), member("SH600000", "银行")),
                        SNAPSHOT,
                        WINDOW);
        ValuationParams params = ValuationParams.defaults();

        ConductionFactor.Result topHeat =
                ConductionFactor.compute(assoc.get("SZ300024"), heat, params);
        ConductionFactor.Result lowHeat =
                ConductionFactor.compute(assoc.get("SH600000"), heat, params);

        assertThat(topHeat.score()).isEqualTo(30.0); // 热度第 1 名（heatNorm=1.0）封顶恰 30.0
        assertThat(lowHeat.score()).isGreaterThan(0.0).isLessThan(30.0);
        assertThat(topHeat.assoc().get(0).source()).isEqualTo("INDUSTRY_MEMBER");
    }

    // ---- M29 T256：分市场白名单（港美股 F10 枚举进出边——A 股委托零回归） ----

    @Test
    void marketScoped_hkWhitelist_swOnlyNamesRejected() {
        // 港股口径：HK 枚举（软件服务/银行）出边；SW-only 名（美容护理）与 UNKNOWN 兜底不出边
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(
                                eventLink(List.of("HK00700"), List.of("软件服务", "美容护理"), 1),
                                eventLink(List.of("HK09988"), List.of("UNKNOWN"), 1)),
                        List.of(newsLink(List.of("HK00700"), "银行", "美容护理", 2)),
                        List.of(new IndustryAssociator.MemberLink("HK09988", "软件服务")),
                        SNAPSHOT,
                        WINDOW,
                        com.info.platform.domain.aggregation.Market.HK);

        assertThat(assoc.get("HK00700"))
                .extracting(IndustryAssociator.Association::industry)
                .containsExactlyInAnyOrder("软件服务", "银行");
        assertThat(assoc.get("HK09988"))
                .extracting(IndustryAssociator.Association::industry)
                .containsExactly("软件服务"); // UNKNOWN 兜底不出边
    }

    @Test
    void marketScoped_usWhitelist_usEnumsLinked() {
        Map<String, List<IndustryAssociator.Association>> assoc =
                IndustryAssociator.associate(
                        List.of(eventLink(List.of("USAAPL"), List.of("软件与信息服务"), 0)),
                        List.of(),
                        List.of(),
                        SNAPSHOT,
                        WINDOW,
                        com.info.platform.domain.aggregation.Market.US);

        assertThat(assoc.get("USAAPL")).hasSize(1);
        assertThat(assoc.get("USAAPL").get(0).industry()).isEqualTo("软件与信息服务");
    }

    @Test
    void marketScoped_aShareDelegation_identicalToLegacyOverloads() {
        // A 股委托零回归：market=A_SHARE 六参与既有五参逐值一致
        List<IndustryAssociator.EventLink> events =
                List.of(eventLink(List.of("SH600519"), List.of("食品饮料"), 3));
        List<IndustryAssociator.NewsLink> news =
                List.of(newsLink(List.of("SH600519"), "银行", "房地产", 5));
        List<IndustryAssociator.MemberLink> members =
                List.of(new IndustryAssociator.MemberLink("SH600519", "食品饮料"));
        Map<String, List<IndustryAssociator.Association>> viaMarket =
                IndustryAssociator.associate(
                        events,
                        news,
                        members,
                        SNAPSHOT,
                        WINDOW,
                        com.info.platform.domain.aggregation.Market.A_SHARE);
        Map<String, List<IndustryAssociator.Association>> legacy =
                IndustryAssociator.associate(events, news, members, SNAPSHOT, WINDOW);
        assertThat(viaMarket).isEqualTo(legacy);
    }
}
