package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.SqueezeJudge.Affected;
import com.info.platform.domain.markettop.SqueezeJudge.Candidate;
import com.info.platform.domain.markettop.SqueezeJudge.Swap;
import com.info.platform.domain.markettop.SqueezeJudge.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SqueezeJudge 迟滞判定单测（M22 T191，方案 §4.3.2 边界穷举）：恰差 gap 不过阈 / 超阈换位 / 挤出对称同阈 / 多换位配对 /
 * affected 全在榜成员不变 → NO_LINK / 并列破并列确定性 / 粗筛排除复检（ST/无信号）/ 无基准（空 Top10）恒不换位。
 */
class SqueezeJudgeTest {

    private static final double GAP = 0.5;

    private static Candidate top(long id, double finalScore) {
        return new Candidate(id, "SH" + id, "在榜" + id, finalScore);
    }

    private static Affected affected(long id, double total, double f1, double f2, String name) {
        return new Affected(id, "SZ" + id, name, total, f1, f2);
    }

    private static List<Candidate> top10Baseline() {
        List<Candidate> top = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            top.add(top(id, 60.0));
        }
        return top;
    }

    @Test
    void entrantExactlyAtExitPlusGap_noSwap() {
        // 恰差 gap：entrant 60.5 = exit 60 + 0.5 → 不满足「严格大于」→ NO_LINK（防抖红线）
        Verdict verdict = SqueezeJudge.judge(top10Baseline(), List.of(affected(11, 60.5, 40, 30, "新贵")), GAP);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.swaps()).isEmpty();
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).doesNotContain(11L);
    }

    @Test
    void entrantAboveExitPlusGap_swapsIn() {
        Verdict verdict = SqueezeJudge.judge(top10Baseline(), List.of(affected(11, 70.0, 40, 30, "新贵")), GAP);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.swaps()).hasSize(1);
        assertThat(verdict.swaps().get(0).entrant().subjectId()).isEqualTo(11L);
        assertThat(verdict.swaps().get(0).exit().subjectId()).isEqualTo(10L); // 榜尾被挤出
        assertThat(verdict.newTop().get(0).subjectId()).isEqualTo(11L); // final 降序居首
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).doesNotContain(10L);
    }

    @Test
    void squeezeOut_symmetricSameThreshold() {
        // 挤出对称：在榜 A(1) 增量重评后跌至 60.2，榜外受影响 B(11) 72 → B 换入 A 换出（72 > 60.2 + 0.5）
        List<Candidate> top = top10Baseline();
        Verdict verdict = SqueezeJudge.judge(top, List.of(affected(1, 60.2, 40, 30, "在榜一"), affected(11, 72.0, 40, 30, "新贵")), GAP);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.swaps()).hasSize(1);
        assertThat(verdict.swaps().get(0).exit().subjectId()).isEqualTo(1L);
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).doesNotContain(1L);
    }

    @Test
    void squeezeOut_belowThreshold_keepsOldOrder() {
        // 跌出侧同阈迟滞：A 跌至 60.2，B 仅 60.4 → 60.4 ≤ 60.2 + 0.5 → 不换位（小幅波动不折腾）
        List<Candidate> top = top10Baseline();
        Verdict verdict = SqueezeJudge.judge(top, List.of(affected(1, 60.2, 40, 30, "在榜一"), affected(11, 60.4, 40, 30, "边缘")), GAP);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).contains(1L).doesNotContain(11L);
    }

    @Test
    void multipleSwaps_pairedByFinalOrder() {
        Verdict verdict =
                SqueezeJudge.judge(
                        top10Baseline(),
                        List.of(affected(11, 80.0, 40, 30, "甲"), affected(12, 75.0, 40, 30, "乙")),
                        GAP);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.swaps()).hasSize(2);
        // rank 序配对：强 entrant 配强 exit（80→挤 10；75→挤 9）
        assertThat(verdict.swaps())
                .extracting(swap -> swap.exit().subjectId())
                .containsExactly(10L, 9L);
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).contains(11L, 12L).doesNotContain(9L, 10L);
    }

    @Test
    void affectedAllAlreadyInTop_membershipUnchanged_noPass() {
        // 受影响标的全在榜且分未过阈差 → 成员不变 NO_LINK（零重排）
        Verdict verdict =
                SqueezeJudge.judge(
                        top10Baseline(), List.of(affected(3, 60.3, 40, 30, "在榜三")), GAP);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.newTop())
                .extracting(Candidate::subjectId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void tieScores_brokenBySubjectIdDeterministically() {
        // 并列分确定性：同 final 依 subjectId 升序（同输入同判定——幂等验收锚）
        Verdict first = SqueezeJudge.judge(top10Baseline(), List.of(affected(12, 70.0, 40, 30, "甲"), affected(11, 70.0, 40, 30, "乙")), GAP);
        Verdict second = SqueezeJudge.judge(top10Baseline(), List.of(affected(12, 70.0, 40, 30, "甲"), affected(11, 70.0, 40, 30, "乙")), GAP);

        assertThat(first.newTop()).extracting(Candidate::subjectId).isEqualTo(second.newTop().extracting(Candidate::subjectId));
        assertThat(first.newTop().stream().limit(2).map(Candidate::subjectId).toList()).containsExactly(11L, 12L);
    }

    @Test
    void excludedRecheck_stAndNoSignalAffectedNotInJudgeSet() {
        // 粗筛排除复检：ST 名称（E1）与无信号 F1=0∧F2=0（E2）不入判定集——高分也不换位
        Verdict verdict =
                SqueezeJudge.judge(
                        top10Baseline(),
                        List.of(
                                affected(11, 99.0, 40, 30, "*ST高危"),
                                affected(12, 98.0, 0, 0, "无信号"),
                                affected(13, 70.0, 40, 30, "合格新贵")),
                        GAP);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).contains(13L).doesNotContain(11L, 12L);
    }

    @Test
    void emptyCurrentTop_noExits_neverPasses() {
        // 首日无基准（当日与历史均无版本）：增量不 bootstrap 榜单——无 exits 恒不换位，留给 18:00 全量
        Verdict verdict = SqueezeJudge.judge(List.of(), List.of(affected(11, 99.0, 40, 30, "新贵")), GAP);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.newTop()).extracting(Candidate::subjectId).containsExactly(11L);
    }

    @Test
    void topSizeAlwaysCappedAtTen() {
        List<Affected> many = new ArrayList<>();
        for (long id = 11; id <= 30; id++) {
            many.add(affected(id, 90.0 - id, 40, 30, "候选" + id));
        }
        Verdict verdict = SqueezeJudge.judge(top10Baseline(), many, GAP);

        assertThat(verdict.newTop()).hasSize(10);
    }
}
