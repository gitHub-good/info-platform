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
}
