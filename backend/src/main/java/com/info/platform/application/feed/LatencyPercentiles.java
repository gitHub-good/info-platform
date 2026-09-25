package com.info.platform.application.feed;

import java.util.ArrayList;
import java.util.List;

/**
 * 感知延迟分位数计算（最近邻秩法，M13 stats 端点既有口径抽公用——T114 大盘复用同一实现防两处分位漂移）。
 *
 * <p>最近邻秩：{@code ceil(p% × n) − 1}（clamp 到末位）；无样本返回 null（与 0 可区分）。
 */
final class LatencyPercentiles {

    private LatencyPercentiles() {}

    /** P50/P90 一对（单次排序取两秩；空样本两值为 null）。 */
    record Pair(Long p50, Long p90) {}

    static Pair percentilePair(List<Long> samples) {
        if (samples.isEmpty()) {
            return new Pair(null, null);
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        return new Pair(
                sorted.get(percentileIndex(sorted.size(), 50)),
                sorted.get(percentileIndex(sorted.size(), 90)));
    }

    /** 最近邻秩：ceil(p% × n) − 1（clamp 到 [0, n-1]）。 */
    static int percentileIndex(int size, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * size) - 1;
        return Math.min(Math.max(index, 0), size - 1);
    }
}
