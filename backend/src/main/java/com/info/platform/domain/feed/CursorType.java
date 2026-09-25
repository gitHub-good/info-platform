package com.info.platform.domain.feed;

/**
 * 增量游标类型（M13 T100，方案 §4.3 {@code info_source.config.cursorType}）。
 *
 * <p>游标语义由源声明，决定「已见条目止」的比较口径与 {@code source_poll_state.cursor_value} 的取值：
 */
public enum CursorType {

    /** 数值递增串（源侧条目 id，如金十 {@code id}）：数值比较，newest-first 迭代遇 {@code <=} 已见值止。 */
    ID,
    /** ISO-8601 时间（如 MarketWatch {@code publishedAt}）：时间比较，同上。 */
    TIME,
    /** 纯去重（无稳定游标字段）：不比较游标，靠唯一索引 + INSERT OR IGNORE 收敛（默认）。 */
    NONE;

    /** 大小写不敏感解析；null/空白返回 null（由校验器给字段级提示）。 */
    public static CursorType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (CursorType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }
}
