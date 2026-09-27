package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.DeepDiveOutcome.GenMethod;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Entry;
import com.info.platform.domain.markettop.DeepDiveOutputParser.Parsed;
import com.info.platform.domain.markettop.TopComposer.Composed;
import com.info.platform.domain.markettop.TopComposer.ScoredSubject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TopComposer 单测（M21 T183，方案 §4.5.1 合成公式 + ADR-0059 裁决 5）： diveScore
 * 算术（25+10×min(亮点,3)+10×min(风险,2)+5×min(引用,5)， 合格区间 [75,100]）/ max 包络（高分不被拉低）/ factor_only 与 FULL
 * 同序可比 / final 排序四键 / 恰 10 截断与不足 10 如实。
 */
class TopComposerTest {

    /** 合格深析终态（条目引用共享，独立引用数直接构造——diveScore 引用口径 = citations 并集数）。 */
    private static DeepDiveOutcome fullDive(int highlights, int risks, int distinctCitations) {
        Citation shared = new Citation("EVENT", 1);
        Parsed parsed =
                new Parsed(
                        "论点",
                        entries(highlights, List.of(shared)),
                        entries(risks, List.of(shared)),
                        List.of());
        DeepDiveOutcome base = DeepDiveOutcome.llm(parsed);
        List<Citation> spread = new ArrayList<>();
        for (int i = 0; i < distinctCitations; i++) {
            spread.add(new Citation(i % 2 == 0 ? "EVENT" : "NEWS", i + 1));
        }
        return new DeepDiveOutcome(
                GenMethod.LLM,
                base.thesis(),
                base.highlights(),
                base.risks(),
                List.copyOf(spread),
                base.summary());
    }

    private static List<Entry> entries(int count, List<Citation> citations) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Entry("条目" + i, citations))
                .toList();
    }

    private static ScoredSubject scored(
            long id,
            String name,
            double total,
            double fCatalyst,
            String lastEventDate,
            DeepDiveOutcome dive) {
        return new ScoredSubject(
                id, "SH" + id, name, total, fCatalyst, lastEventDate, true, 99.0, 3, dive);
    }

    @Test
    void diveScore_arithmeticWithinBounds() {
        // Arrange + Act + Assert：2 亮点 2 风险 3 引用 → 25 + 10×2 + 10×2 + 5×3 = 80
        assertThat(TopComposer.diveScore(fullDive(2, 2, 3))).isEqualTo(80.0);
        // 上限封顶：5 亮点 4 风险 9 引用 → 25+30+20+25 = 100（min 截断）
        assertThat(TopComposer.diveScore(fullDive(5, 4, 9))).isEqualTo(100.0);
        // 最小合格形态 2 亮点 2 风险 2 引用 = 25+20+20+10 = 75（合格区间下界）
        assertThat(TopComposer.diveScore(fullDive(2, 2, 2))).isEqualTo(75.0);
    }

    @Test
    void compose_maxEnvelope_highScoreNotPulledDown() {
        // Arrange：总分 80 的 FULL 标的（diveScore 75）：0.8×80+0.2×75 = 79 < 80 → max 包络保 80
        ScoredSubject high = scored(1, "高分标的", 80.0, 10, "2026-09-21", fullDive(2, 2, 2));

        List<Composed> composed =
                TopComposer.compose(List.of(high), new TopComposer.Config(300, 40, 0.30));

        assertThat(composed).hasSize(1);
        assertThat(composed.get(0).finalScore()).isEqualTo(80.0);

        // 低分标的被拉高但受 0.2 权重幅面约束（50 → 60，恰 +10 = 0.2×(100-50)）
        ScoredSubject low = scored(2, "低分标的", 50.0, 10, "2026-09-21", fullDive(5, 4, 9));
        List<Composed> boosted =
                TopComposer.compose(List.of(low), new TopComposer.Config(300, 40, 0.30));
        assertThat(boosted.get(0).finalScore()).isEqualTo(60.0);
    }

    @Test
    void compose_factorOnlyFinalEqualsTotal_comparableOrdering() {
        // Arrange：同分 FULL（dive 75 → final = max(50, 40+15) = 55）与 FACTOR_ONLY（final=50）同序可比
        ScoredSubject full = scored(1, "深析标的", 50.0, 10, "2026-09-21", fullDive(2, 2, 2));
        ScoredSubject factorOnly = scored(2, "纯因子标的", 50.0, 10, "2026-09-21", null);
        ScoredSubject template =
                scored(3, "模板兜底标的", 50.0, 10, "2026-09-21", DeepDiveOutcome.template("模板文案"));

        List<Composed> composed =
                TopComposer.compose(
                        List.of(factorOnly, template, full), new TopComposer.Config(300, 40, 0.30));

        // FULL 加分居首；factor_only 与 TEMPLATE 均 final=总分 且 generation=FACTOR_ONLY（模板不参与 diveScore）
        assertThat(composed).extracting(Composed::subjectId).containsExactly(1L, 2L, 3L);
        assertThat(composed.get(0).generation()).isEqualTo("FULL");
        assertThat(composed.get(0).finalScore()).isEqualTo(55.0);
        assertThat(composed.get(1).generation()).isEqualTo("FACTOR_ONLY");
        assertThat(composed.get(1).diveScore()).isNull();
        assertThat(composed.get(1).diveMethod()).isNull();
        assertThat(composed.get(2).generation()).isEqualTo("FACTOR_ONLY");
        assertThat(composed.get(2).diveMethod()).isEqualTo("TEMPLATE");
        assertThat(composed.get(2).finalScore()).isEqualTo(50.0);
    }

    @Test
    void compose_ordering_fourKeysDeterministic() {
        // Arrange：final 并列 → f_catalyst 降序 → last_event_date 降序（NULL 最后）→ subject_id 升序
        ScoredSubject a = scored(9, "甲", 50.0, 10, "2026-09-20", null);
        ScoredSubject b = scored(5, "乙", 50.0, 20, "2026-09-18", null);
        ScoredSubject c = scored(7, "丙", 50.0, 20, "2026-09-21", null);
        ScoredSubject d = scored(3, "丁", 50.0, 20, null, null);
        ScoredSubject e = scored(1, "戊", 50.0, 20, null, null);

        List<Composed> composed =
                TopComposer.compose(List.of(a, b, c, d, e), new TopComposer.Config(300, 40, 0.30));

        // 丙(7) 日期最新 → 乙(5) → 丁(3)/戊(1) 均 NULL 并列 → subject_id 升序 1 先于 3 → 甲(9) f_catalyst 最低
        assertThat(composed).extracting(Composed::subjectId).containsExactly(7L, 5L, 1L, 3L, 9L);
    }

    @Test
    void compose_truncatesAtTen_andShortfallsHonest() {
        // Arrange：12 只递增分 → 恰 10 截断（topSize 代码常量不配置——蓝图锁死）
        List<ScoredSubject> twelve =
                java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(i -> scored(i, "标的" + i, 40 + i, 5, null, null))
                        .toList();

        List<Composed> composed =
                TopComposer.compose(twelve, new TopComposer.Config(300, 40, 0.30));

        assertThat(composed).hasSize(TopComposer.TOP_SIZE);
        assertThat(composed.get(0).subjectId()).isEqualTo(12L);

        // 不足 10 如实：7 只全入榜
        List<Composed> shortfall =
                TopComposer.compose(twelve.subList(0, 7), new TopComposer.Config(300, 40, 0.30));
        assertThat(shortfall).hasSize(7);
    }

    @Test
    void basis_reflectsConfig() {
        assertThat(TopComposer.basis(new TopComposer.Config(300, 40, 0.30)))
                .isEqualTo(
                        "mt-v1:final=max(total,0.8*total+0.2*dive);dive=25|10x3|10x2|5x5;"
                                + "pool=300;dive=40;cap=0.30");
    }
}
