package com.info.platform.infrastructure.aggregation;

/**
 * 字段映射转换规则。
 *
 * <p>JSON 配置值用小写下划线（{@code none} / {@code to_decimal} / {@code to_long} / {@code to_string} /
 * {@code to_iso_date} / {@code to_iso_datetime} / {@code epoch_seconds_to_iso} / {@code
 * strip_html}）， {@link #from} 大小写不敏感解析，缺失或空串视作 {@link #NONE}。
 *
 * <p>M13 新增三值（方案 §4.3，资讯源通用 JSON 引擎用；既有六源映射零影响）： {@code to_iso_datetime}（{@code yyyy-MM-dd
 * HH:mm[:ss]} 墙钟时间按 Asia/Shanghai → ISO-8601 UTC 秒）/ {@code epoch_seconds_to_iso}（Unix 秒 → ISO-8601
 * UTC）/ {@code strip_html}（jsoup 抽纯文本，richtext 源用）。
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
    TO_ISO_DATE,
    /** {@code yyyy-MM-dd HH:mm[:ss]} 墙钟 → ISO-8601 UTC 秒（按 Asia/Shanghai，M13） */
    TO_ISO_DATETIME,
    /** Unix 秒（数字/字符串）→ ISO-8601 UTC 秒（M13） */
    EPOCH_SECONDS_TO_ISO,
    /** HTML 片段抽纯文本（jsoup，richtext 源，M13） */
    STRIP_HTML;

    /** 大小写不敏感解析；null/空白 → {@link #NONE}；未知值抛 IllegalArgumentException。 */
    public static Transform from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return Transform.valueOf(value.trim().toUpperCase());
    }
}
