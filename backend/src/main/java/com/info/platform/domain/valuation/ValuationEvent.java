package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import java.time.LocalDate;

/**
 * 因子计算的事件投影（{@code event_item} 窗内行 → 领域纯函数输入，M20 方案 §4.2 记号 E(s,W) 的单元素）。
 *
 * <p>窗口与衰减全部锚定 {@code snapshotDate}（调用方传入，非当前时刻——幂等基石）；event_date 为 Asia/Shanghai yyyy-MM-dd（V23
 * 落库口径）。
 */
public record ValuationEvent(
        long eventId,
        String summary,
        LocalDate eventDate,
        Direction direction,
        Importance importance,
        EventType eventType) {}
