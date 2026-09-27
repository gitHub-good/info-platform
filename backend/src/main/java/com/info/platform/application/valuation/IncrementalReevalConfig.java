package com.info.platform.application.valuation;

import com.info.platform.domain.analysis.Importance;

/**
 * 增量重评配置载荷（{@code incremental.reeval} 键，M22 方案 §3.5-1）：读侧 {@link IncrementalReevalSettings}
 * 字段级回退缺省； 写侧 {@code IncrementalReevalConfigValidator}（30092）把关。
 *
 * @param minScoreGap 挤入挤出双向同阈迟滞（缺省 0.5，0~10——entrant.final &gt; exit.final + gap 才换位）
 * @param linkMinIntervalMinutes 联动最小间隔分钟（缺省 10，0~60——窗口内本轮 DEFERRED 下轮重试）
 * @param scanWindowHours 事件扫描补跑窗小时（缺省 24，1~72——tick 丢失由窗内补扫 + 全量兜底）
 * @param eventBufferSeconds 落库缓冲秒（缺省 20，0~120——缓冲内事件留待下轮，防读半行）
 * @param minImportance 触发阈值重要度（缺省 HIGH——枚举序比较，变更留痕走配置中心）
 */
public record IncrementalReevalConfig(
        double minScoreGap,
        int linkMinIntervalMinutes,
        int scanWindowHours,
        int eventBufferSeconds,
        Importance minImportance) {

    /** 代码缺省（方案 §3.5-1 冻结值）。 */
    public static IncrementalReevalConfig defaults() {
        return new IncrementalReevalConfig(0.5, 10, 24, 20, Importance.HIGH);
    }
}
