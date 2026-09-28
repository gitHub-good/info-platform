package com.info.platform.domain.mainline;

import java.util.List;
import java.util.Map;

/**
 * 行业内百分位纯函数（M27 T243/T244 共用，方案 §3.4 口径）：{@code pctile = 100 × |{v ∈ values : v < x}| / (n − 1)}。
 *
 * <p>升序 0 基秩口径：最低值得 0、最高值得 100；同值行共享同一百分位（严格小于计数——并列天然同名次取小秩，确定性）。 null 值不参与秩分母（缺数行得中性 50，由调用方处理）。n
 * ≤ 1 时全 50（单元素无序可比）。
 */
public final class Percentiles {

    /** 无序可比时的中性分（缺数/单元素口径，沿 M20 f_valuation=50 中性先例）。 */
    public static final double NEUTRAL = 50d;

    private Percentiles() {}

    /**
     * 计算一组（键 → 原始值）的百分位视图。
     *
     * @param values 键 → 原始值（null 值行视为缺数）
     * @return 同键集 → 百分位 [0,100]（缺数行 50 中性；空输入/单有效值全 50）
     */
    public static <K> Map<K, Double> of(Map<K, Double> values) {
        Map<K, Double> result = new java.util.LinkedHashMap<>();
        List<Double> present = values.values().stream().filter(java.util.Objects::nonNull).toList();
        if (present.size() <= 1) {
            values.keySet().forEach(key -> result.put(key, NEUTRAL));
            return result;
        }
        for (Map.Entry<K, Double> entry : values.entrySet()) {
            Double raw = entry.getValue();
            if (raw == null) {
                result.put(entry.getKey(), NEUTRAL);
                continue;
            }
            long strictlyLess = present.stream().filter(v -> v < raw).count();
            result.put(entry.getKey(), 100d * strictlyLess / (present.size() - 1));
        }
        return result;
    }

    /** 降序名次（1 基，并列取小秩——rank = |{v > x}| + 1；null 记 null）。 */
    public static <K> Map<K, Integer> rankDesc(Map<K, Double> values) {
        Map<K, Integer> result = new java.util.LinkedHashMap<>();
        List<Double> present = values.values().stream().filter(java.util.Objects::nonNull).toList();
        for (Map.Entry<K, Double> entry : values.entrySet()) {
            Double raw = entry.getValue();
            if (raw == null) {
                result.put(entry.getKey(), null);
                continue;
            }
            long strictlyGreater = present.stream().filter(v -> v > raw).count();
            result.put(entry.getKey(), (int) strictlyGreater + 1);
        }
        return result;
    }
}
