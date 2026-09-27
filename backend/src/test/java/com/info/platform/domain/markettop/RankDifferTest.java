package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.RankDiffer.Change;
import com.info.platform.domain.markettop.RankDiffer.Diff;
import com.info.platform.domain.markettop.RankDiffer.Dropped;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RankDiffer 单测（M21 T183，方案 §4.5.2 变动留痕）：首日全 NEW / UP/DOWN/SAME 四态 / 跌出留痕 / change 语义按名次对比。 */
class RankDifferTest {

    private static PrevSubject prev(long id, String code, String name, int rankNo) {
        return new PrevSubject(id, code, name, rankNo);
    }

    @Test
    void diff_firstDay_noPrevious_allNewNoDropped() {
        Diff diff = RankDiffer.diff(List.of(), List.of(1L, 2L, 3L));

        assertThat(diff.dropped()).isEmpty();
        assertThat(diff.changes()).hasSize(3);
        assertThat(diff.changes().get(1L)).isEqualTo(new Change(null, "NEW"));
    }

    @Test
    void diff_upDownSameNew_allFourStates() {
        // Arrange：昨日 1甲 2乙 3丙 4丁；今日 3丙(→1 UP) 1甲(→2 DOWN) 2乙(→3 DOWN) 5戊(→4 NEW)
        List<PrevSubject> prevTop =
                List.of(
                        prev(1, "SH1", "甲", 1), prev(2, "SH2", "乙", 2),
                        prev(3, "SH3", "丙", 3), prev(4, "SH4", "丁", 4));

        Diff diff = RankDiffer.diff(prevTop, List.of(3L, 1L, 2L, 5L));

        assertThat(diff.changes().get(3L)).isEqualTo(new Change(3, "UP"));
        assertThat(diff.changes().get(1L)).isEqualTo(new Change(1, "DOWN"));
        assertThat(diff.changes().get(2L)).isEqualTo(new Change(2, "DOWN"));
        assertThat(diff.changes().get(5L)).isEqualTo(new Change(null, "NEW"));
    }

    @Test
    void diff_sameRank_sameType() {
        List<PrevSubject> prevTop = List.of(prev(1, "SH1", "甲", 1), prev(2, "SH2", "乙", 2));

        Diff diff = RankDiffer.diff(prevTop, List.of(1L, 2L));

        assertThat(diff.changes().get(1L)).isEqualTo(new Change(1, "SAME"));
        assertThat(diff.changes().get(2L)).isEqualTo(new Change(2, "SAME"));
        assertThat(diff.dropped()).isEmpty();
    }

    @Test
    void diff_droppedSubjects_leftWithPrevRankAndName() {
        // Arrange：昨日 4 只，今日仅 1 只 → 3 只跌出留痕（[{code,name,prevRank}]）
        List<PrevSubject> prevTop =
                List.of(
                        prev(1, "SH1", "甲", 1), prev(2, "SH2", "乙", 2),
                        prev(3, "SZ3", "丙", 3), prev(4, "SH4", "丁", 4));

        Diff diff = RankDiffer.diff(prevTop, List.of(2L));

        assertThat(diff.dropped())
                .containsExactly(
                        new Dropped("SH1", "甲", 1),
                        new Dropped("SZ3", "丙", 3),
                        new Dropped("SH4", "丁", 4));
        // 昨日第 2 → 今日第 1 = UP
        assertThat(diff.changes().get(2L)).isEqualTo(new Change(2, "UP"));
    }
}
