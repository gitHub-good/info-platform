package com.info.platform.infrastructure.aggregation;

/**
 * 字段映射转换规则。
 *
 * <p>JSON 配置值用小写下划线（{@code none} / {@code to_decimal} / {@code to_long} / {@code to_string} /
 * {@code to_iso_date}）， {@link #from} 大小写不敏感解析，缺失或空串视作 {@link #NONE}。
 */
public enum Transform {
    /** 原样透传 */
    NONE,
    /** 转 {@link java.math.BigDecimal}（字符串去千分位逗号） */
    TO_DECIMAL,
    /** 转 {@code long} */
    TO_LONG,
    /** 转 {@link String} */
    TO_STRING,
    /** 转 ISO-8601 日期/时间字符串 */
    TO_ISO_DATE;

    /** 大小写不敏感解析；null/空白 → {@link #NONE}；未知值抛 IllegalArgumentException。 */
    public static Transform from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return Transform.valueOf(value.trim().toUpperCase());
    }
}
