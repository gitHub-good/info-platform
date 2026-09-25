package com.info.platform.domain.feed;

import java.util.List;

/**
 * 单源一轮取数结果（M13 T101，ADR-0041）。
 *
 * <p>在方案 §4.3 草案的 {@code List<RawFeedItem>} 之上增加 {@code truncated} 补抓截断信号——「深翻触页上限仍有未见条目 →
 * last_round_detail 记 backfill=truncated」要求引擎上报页耗尽事实，摄取服务侧无法从条目列表推断。
 *
 * @param items newest-first 条目（已按游标止步过滤）
 * @param truncated 深翻触顶仍未确认追平（条目可能已滚出源侧窗口，漏抓风险显性化）
 */
public record FetchResult(List<RawFeedItem> items, boolean truncated) {

    /** 正常结果（无截断）。 */
    public static FetchResult of(List<RawFeedItem> items) {
        return new FetchResult(items, false);
    }
}
