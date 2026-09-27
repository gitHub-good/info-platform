package com.info.platform.domain.markettop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 榜单变动 diff（M21 T183，方案 §4.5.2 + ADR-0059 裁决 6，domain 纯函数）：与昨日最新版本对比生成行内 prev_rank/change_type
 * （NEW/UP/DOWN/SAME，rank_no 对比）与跌出留痕（dropped_subjects）。首日无昨日榜单 → 全 NEW； diff
 * 确定性可由两版本重算——留痕是读优化非真相源。
 */
public final class RankDiffer {

    /** 变动类型常量（change_type 列值）。 */
    public static final String NEW = "NEW";

    public static final String UP = "UP";

    public static final String DOWN = "DOWN";

    public static final String SAME = "SAME";

    private RankDiffer() {}

    /** 昨日榜单行（prev 投影）。 */
    public record PrevSubject(long subjectId, String code, String name, int rankNo) {}

    /** 行内变动（prevRank=null 即昨日不在榜 → NEW）。 */
    public record Change(Integer prevRank, String changeType) {}

    /** 跌出留痕（batch.dropped_subjects 元素）。 */
    public record Dropped(String code, String name, int prevRank) {}

    /** diff 结果（changes 按当前榜单行序消费）。 */
    public record Diff(Map<Long, Change> changes, List<Dropped> dropped) {}

    /**
     * diff（确定性纯函数）。
     *
     * @param prevTop 昨日最新版本 top 行（rank_no 升序；空 = 首日 → 全 NEW）
     * @param currentRankedIds 今日入榜 subjectId（榜单名次序，index+1 = rank_no）
     */
    public static Diff diff(List<PrevSubject> prevTop, List<Long> currentRankedIds) {
        Map<Long, Integer> prevRanks = new LinkedHashMap<>();
        for (PrevSubject prev : prevTop) {
            prevRanks.put(prev.subjectId(), prev.rankNo());
        }

        Map<Long, Change> changes = new LinkedHashMap<>();
        for (int index = 0; index < currentRankedIds.size(); index++) {
            long subjectId = currentRankedIds.get(index);
            int currentRank = index + 1;
            Integer prevRank = prevRanks.remove(subjectId);
            changes.put(subjectId, new Change(prevRank, changeType(prevRank, currentRank)));
        }

        // prevRanks 剩余 = 昨日在榜今日跌出（按昨日名次序留痕）
        List<Dropped> dropped =
                prevTop.stream()
                        .filter(prev -> prevRanks.containsKey(prev.subjectId()))
                        .map(prev -> new Dropped(prev.code(), prev.name(), prev.rankNo()))
                        .toList();
        return new Diff(changes, dropped);
    }

    private static String changeType(Integer prevRank, int currentRank) {
        if (prevRank == null) {
            return NEW;
        }
        if (currentRank < prevRank) {
            return UP;
        }
        return currentRank > prevRank ? DOWN : SAME;
    }
}
