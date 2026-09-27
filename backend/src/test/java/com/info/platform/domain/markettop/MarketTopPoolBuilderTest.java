package com.info.platform.domain.markettop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.markettop.MarketTopPoolBuilder.Candidate;
import com.info.platform.domain.markettop.MarketTopPoolBuilder.PoolConfig;
import com.info.platform.domain.markettop.MarketTopPoolBuilder.PoolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MarketTopPoolBuilder 单测（M21 T181，方案 §4.3 + §6 测试要点）：切分可复现（并列组四键确定性，两连跑池 id 序列相等）/ 30.0 残留组按
 * last_event_date（NULL 最后）破并列再按 subject_id / E1 ST 与 E2 无信号排除留痕 / 合格不足全取如实 / 深析候选池前缀 /
 * 层数断言负向（divePlanned &gt; pool / topSize &gt; divePlanned / 快照行 ≤ 池）/ 非法配置防御。
 */
class MarketTopPoolBuilderTest {

    private static final PoolConfig CONFIG = new PoolConfig(5, 3);

    private static Candidate candidate(
            long id, String name, double total, double f1, double f2, String lastEventDate) {
        return new Candidate(id, "SH" + id, name, total, f1, f2, lastEventDate);
    }

    @Test
    void build_excludesStAndNoSignal_countsLeftInFunnel() {
        // Arrange：ST 1 只 + 无信号（F1=0∧F2=0）2 只 + 有信号 4 只
        List<Candidate> candidates =
                List.of(
                        candidate(1, "*ST金科", 22.0, 0, 0, null), // E1（E2 同时成立——ST 优先计）
                        candidate(2, "无事件甲", 30.0, 0, 0, "2026-09-01"), // E2
                        candidate(3, "无事件乙", 30.0, 0, 0, "2026-09-02"), // E2
                        candidate(4, "茅台", 58.4, 22.5, 91.2, "2026-09-21"),
                        candidate(5, "平安", 30.0, 5.0, 0, null),
                        candidate(6, "宁德", 43.2, 0, 29.0, "2026-09-18"),
                        candidate(7, "机器人", 60.1, 20.2, 90.0, "2026-09-20"));

        // Act
        PoolResult result = MarketTopPoolBuilder.build(candidates, CONFIG);

        // Assert：排除计数留痕；快照行不动（funnel.snapshotRows = 全量 7）
        assertThat(result.funnel().snapshotRows()).isEqualTo(7);
        assertThat(result.funnel().eligible()).isEqualTo(4);
        assertThat(result.funnel().excluded().st()).isEqualTo(1);
        assertThat(result.funnel().excluded().noSignal()).isEqualTo(2);
        assertThat(result.pool()).extracting(Candidate::subjectId).containsExactly(7L, 4L, 6L, 5L);
        assertThat(result.diveCandidates())
                .extracting(Candidate::subjectId)
                .containsExactly(7L, 4L, 6L); // 池前 deepDiveLimit=3 只
    }

    @Test
    void build_tie30Group_brokenByLastEventDateNullLastThenSubjectId() {
        // OBS-M20-3：30.0 并列残留组——四键后两级破并列（last_event_date DESC NULL 最后 → subject_id ASC）
        List<Candidate> candidates =
                List.of(
                        candidate(11, "甲", 30.0, 0, 29.0, null), // NULL 最旧 → 末位
                        candidate(9, "乙", 30.0, 0, 29.0, "2026-09-15"),
                        candidate(8, "丙", 30.0, 0, 29.0, "2026-09-15"), // 同日期 → id 升序 8 < 9
                        candidate(10, "丁", 31.0, 0, 29.0, null)); // 总分更高首位

        PoolResult result = MarketTopPoolBuilder.build(candidates, new PoolConfig(4, 2));

        assertThat(result.pool())
                .extracting(Candidate::subjectId)
                .containsExactly(10L, 8L, 9L, 11L);
    }

    @Test
    void build_sameInputTwice_identicalPoolSequence_reproducible() {
        // §6 验收用例：固定夹具两连跑池 id 序列相等（四键全序无随机无时钟）
        List<Candidate> candidates =
                List.of(
                        candidate(1, "甲", 30.0, 0, 0.5, "2026-09-01"),
                        candidate(2, "乙", 30.0, 0, 0.5, "2026-09-02"),
                        candidate(3, "丙", 44.0, 12.0, 8.0, null),
                        candidate(4, "丁", 44.0, 12.0, 8.0, "2026-09-10"),
                        candidate(5, "戊", 50.0, 1.0, 2.0, "2026-09-05"),
                        candidate(6, "己", 50.0, 1.0, 2.0, "2026-09-05")); // 四键前三全同 → id 5<6

        PoolResult first = MarketTopPoolBuilder.build(candidates, new PoolConfig(3, 2));
        PoolResult second = MarketTopPoolBuilder.build(candidates, new PoolConfig(3, 2));

        assertThat(first.pool()).extracting(Candidate::subjectId).containsExactly(5L, 6L, 4L);
        assertThat(first.pool()).isEqualTo(second.pool());
        assertThat(first.diveCandidates()).isEqualTo(second.diveCandidates());
        assertThat(first.funnel()).isEqualTo(second.funnel());
    }

    @Test
    void build_inputOrderIrrelevant_fourKeyOrderDeterminesSequence() {
        // 输入顺序打乱 → 同一切分（排序键内含 subject_id 全序，与到达序无关）
        List<Candidate> candidates =
                List.of(
                        candidate(1, "甲", 30.0, 0, 0.5, "2026-09-01"),
                        candidate(2, "乙", 30.0, 0, 0.5, "2026-09-02"),
                        candidate(3, "丙", 44.0, 12.0, 8.0, null));
        List<Candidate> shuffled = new ArrayList<>(candidates);
        java.util.Collections.reverse(shuffled);

        assertThat(MarketTopPoolBuilder.build(shuffled, new PoolConfig(2, 1)).pool())
                .isEqualTo(MarketTopPoolBuilder.build(candidates, new PoolConfig(2, 1)).pool());
    }

    @Test
    void build_eligibleBelowPoolSize_takesAll_asRecorded() {
        // §4.3.2：合格标的 < poolSize 时全取（eligible/poolSize 留痕如实）
        List<Candidate> two =
                List.of(candidate(1, "甲", 40.0, 5, 5, null), candidate(2, "乙", 30.0, 1, 1, null));

        PoolResult result = MarketTopPoolBuilder.build(two, new PoolConfig(300, 40));

        assertThat(result.pool()).hasSize(2);
        assertThat(result.funnel().poolSize()).isEqualTo(2); // 实际值 ≠ 配置 300
        assertThat(result.diveCandidates()).hasSize(2); // 深析候选随池收缩
    }

    @Test
    void build_nullCandidates_emptyPool_funnelZeroed() {
        PoolResult result = MarketTopPoolBuilder.build(null, CONFIG);

        assertThat(result.pool()).isEmpty();
        assertThat(result.funnel().snapshotRows()).isZero();
        assertThat(result.funnel().eligible()).isZero();
    }

    @Test
    void build_invalidConfig_defensiveRejection() {
        assertThatThrownBy(() -> MarketTopPoolBuilder.build(List.of(), new PoolConfig(0, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolSize");
        assertThatThrownBy(() -> MarketTopPoolBuilder.build(List.of(), new PoolConfig(300, -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deepDiveLimit");
    }

    // ---- 层数断言（§4.3.4 需求锁定语义的机制化——Job 阶段 3 落库前调用面） ----

    @Test
    void assertFunnelLayers_legalChain_passes() {
        // 快照行 5221 > 池 300 ≥ 深析 40 ≥ 榜 10（生产形态）
        assertThatCode(() -> MarketTopPoolBuilder.assertFunnelLayers(5221, 300, 40, 10))
                .doesNotThrowAnyException();
        // 榜单不足 10 如实（40 ≥ 7）
        assertThatCode(() -> MarketTopPoolBuilder.assertFunnelLayers(5221, 300, 40, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void assertFunnelLayers_violations_throwWithCounts() {
        // 构造 divePlanned > poolSize → ERROR 中止（宁缺毋错，防全量 LLM 逐股类配置事故静默发生）
        assertThatThrownBy(() -> MarketTopPoolBuilder.assertFunnelLayers(5221, 30, 40, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("池行数须 ≥ 深析候选数")
                .hasMessageContaining("pool=30")
                .hasMessageContaining("divePlanned=40");
        // topSize > divePlanned（配置事故：榜单数超深析候选）
        assertThatThrownBy(() -> MarketTopPoolBuilder.assertFunnelLayers(5221, 300, 40, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("深析候选数须 ≥ 榜单行数");
        // 快照行 ≤ 池（池吞全量——「全量 LLM 逐股」形态即此处拦截）
        assertThatThrownBy(() -> MarketTopPoolBuilder.assertFunnelLayers(300, 300, 40, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照行数须严格大于池行数");
    }

    @Test
    void stRule_matchesM20RiskFactorSemantics() {
        // 大小写敏感 contains（A 股命名大写 ST）：*ST金科/ST人福 命中；「斯顿」不命中
        assertThat(MarketTopPoolBuilder.isSt(candidate(1, "*ST金科", 30, 0, 0, null))).isTrue();
        assertThat(MarketTopPoolBuilder.isSt(candidate(1, "ST人福", 30, 0, 0, null))).isTrue();
        assertThat(MarketTopPoolBuilder.isSt(candidate(1, "斯顿尔", 30, 0, 0, null))).isFalse();
        assertThat(MarketTopPoolBuilder.isSt(candidate(1, null, 30, 0, 0, null))).isFalse();
        // E2：F1 与 F2 任一非零即有信号
        assertThat(MarketTopPoolBuilder.isNoSignal(candidate(1, "甲", 30, 0, 0, null))).isTrue();
        assertThat(MarketTopPoolBuilder.isNoSignal(candidate(1, "甲", 30, 0.1, 0, null))).isFalse();
        assertThat(MarketTopPoolBuilder.isNoSignal(candidate(1, "甲", 30, 0, 0.1, null))).isFalse();
    }
}
