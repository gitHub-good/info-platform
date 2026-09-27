package com.info.platform.domain.markettop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 挤入挤出判定器（domain 纯函数，M22 T191，方案 §4.3.2 + ADR-0061 裁决 3）：判定集 = 当日 Top10 ∪ 受影响标的（受影响侧过粗筛排除复检——
 * ST/无信号标的不入判定集），按 final 分降序取前 10 为 newTop；换位须过<b>双向同阈迟滞</b> {@code entrant.final &gt; exit.final +
 * gap} （挤出对称成立——单事件小幅波动不触发重排，防抖红线）。
 *
 * <ul>
 *   <li>final 口径：受影响侧 = 因子总分（深析幅面在联动段继承时合成——判定输入即快照行，方案 §3.3-2 注记）；在榜侧 = 榜单行 final 原值；
 *   <li>确定性：纯内存列表排序（final DESC → subjectId ASC 破并列），无时钟无随机——同输入同判定（边界可单测穷举）；
 *   <li>静态近榜候选（第 11 名起）不参与——其相对序未变，留给 18:00 全量修正（增量判定是「快照式局部正确」，非全序重算，§3.3-1 边界）。
 * </ul>
 */
public final class SqueezeJudge {

    private SqueezeJudge() {}

    /** 判定集成员（在榜侧）。 */
    public record Candidate(long subjectId, String code, String name, double finalScore) {}

    /** 受影响标的（快照行投影——携带粗筛排除复检判据字段）。 */
    public record Affected(
            long subjectId,
            String code,
            String name,
            double finalScore,
            double fCatalyst,
            double fConduction) {}

    /** 一次换位（迟滞过阈的 entrant/exit 对，rank 序配对）。 */
    public record Swap(Candidate entrant, Candidate exit) {}

    /** 判定结果：passed = 过阈换位 ≥1（否则 NO_LINK——成员不变或变动未过阈均不重排）。 */
    public record Verdict(boolean passed, List<Candidate> newTop, List<Swap> swaps) {}

    /** E1 判定子串（PoolBuilder 同口径：A 股命名大写 ST——含 *ST）。 */
    static final String ST_MARKER = "ST";

    /**
     * 判定（确定性纯函数）。
     *
     * @param currentTop 当日最新版本 Top10（rank_no 任意序；空 = 首日无基准 → 无 exits 恒不换位）
     * @param affected 受影响标的（重算后快照行；粗筛排除复检在内完成）
     * @param minScoreGap 迟滞阈值（0~10 可配，缺省 0.5——值域校验归 IncrementalReevalConfigValidator）
     */
    public static Verdict judge(
            List<Candidate> currentTop, List<Affected> affected, double minScoreGap) {
        List<Candidate> safeTop = currentTop == null ? List.of() : currentTop;
        Map<Long, Candidate> candidates = new LinkedHashMap<>(); // final 降序入表，同分 id 升序
        for (Candidate top : safeTop) {
            candidates.putIfAbsent(top.subjectId(), top);
        }
        for (Affected subject : affected == null ? List.<Affected>of() : affected) {
            if (excluded(subject)) {
                continue; // 粗筛排除复检：ST/无信号标的不入判定集（§3.3-1）
            }
            candidates.putIfAbsent(
                    subject.subjectId(),
                    new Candidate(
                            subject.subjectId(),
                            subject.code(),
                            subject.name(),
                            subject.finalScore()));
        }
        List<Candidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(
                Comparator.comparingDouble(Candidate::finalScore)
                        .reversed()
                        .thenComparingLong(Candidate::subjectId));
        List<Candidate> newTop =
                List.copyOf(ordered.subList(0, Math.min(TopComposer.TOP_SIZE, ordered.size())));

        Set<Long> newIds = new LinkedHashSet<>();
        for (Candidate member : newTop) {
            newIds.add(member.subjectId());
        }
        List<Candidate> entrants = new ArrayList<>(); // newTop − top（final 降序）
        for (Candidate member : newTop) {
            if (!containsId(safeTop, member.subjectId())) {
                entrants.add(member);
            }
        }
        List<Candidate> exits = new ArrayList<>(); // top − newTop（原榜序）
        for (Candidate member : safeTop) {
            if (!newIds.contains(member.subjectId()) && !containsId(exits, member.subjectId())) {
                exits.add(member);
            }
        }
        exits.sort(
                Comparator.comparingDouble(Candidate::finalScore)
                        .reversed()
                        .thenComparingLong(Candidate::subjectId));

        // 双向同阈迟滞：entrant[i] 须 > exit[i].final + gap 才换位（对称阈——挤出侧同理由配对表达）
        List<Swap> swaps = new ArrayList<>();
        int pairs = Math.min(entrants.size(), exits.size());
        for (int i = 0; i < pairs; i++) {
            Candidate entrant = entrants.get(i);
            Candidate exit = exits.get(i);
            if (entrant.finalScore() > exit.finalScore() + minScoreGap) {
                swaps.add(new Swap(entrant, exit));
            }
        }
        return new Verdict(!swaps.isEmpty(), newTop, List.copyOf(swaps));
    }

    /** 粗筛排除复检（PoolBuilder E1/E2 同口径）：名称含 ST 或无信号（F1=0 ∧ F2=0）。 */
    static boolean excluded(Affected subject) {
        String name = subject.name() == null ? "" : subject.name();
        return name.contains(ST_MARKER)
                || (subject.fCatalyst() == 0.0 && subject.fConduction() == 0.0);
    }

    private static boolean containsId(List<Candidate> list, long subjectId) {
        for (Candidate candidate : list) {
            if (candidate.subjectId() == subjectId) {
                return true;
            }
        }
        return false;
    }
}
