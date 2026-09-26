package com.info.platform.domain.valuation;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.Importance;
import java.time.LocalDate;

/**
 * 因子明细条目（§4.5 契约 catalyst/fundamental/risk 三维 entries 元素，cap 10 按贡献降序）：eventId 可下钻既有事件卡
 * 溯源链（trace-v1），coef 为该条的合成系数（含方向/类型/重要度乘积），decay 为时间衰减因子——乘积即该条贡献。
 */
public record FactorEntry(
        long eventId,
        String summary,
        LocalDate eventDate,
        Direction direction,
        Importance importance,
        double coef,
        double decay) {}
