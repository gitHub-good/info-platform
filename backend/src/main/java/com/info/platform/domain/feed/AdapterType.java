package com.info.platform.domain.feed;

/**
 * 资讯源适配通道类型（M13 T100，ADR-0038）。
 *
 * <p>持久化为 {@code info_source.adapter_type} 小写下划线文本（{@link #wireCode()}）； 页面新增向导只开放 {@link
 * #RSS}/{@link #JSON_API}（蓝图裁决 1），{@link #HTML_TEMPLATE} 仅预留枚举值不实现（V3 候选）。
 */
public enum AdapterType {

    /** 通用 RSS 2.0/Atom 引擎（jsoup XML 解析，零新依赖）。 */
    RSS("rss"),
    /** 通用 JSON API 引擎（包装剥离 + listPath 导航 + FieldMapper 白名单映射）。 */
    JSON_API("json_api"),
    /** 预置代码适配（{@code adapter_ref} 指向 Spring bean，解析/翻页/清洗全在代码内）。 */
    PRESET("preset"),
    /** 通用 HTML 模板引擎（预留不开放，页面新增向导不出现；V3 候选，ADR-0038）。 */
    HTML_TEMPLATE("html_template");

    private final String wireCode;

    AdapterType(String wireCode) {
        this.wireCode = wireCode;
    }

    /** 持久化/REST 线格式（小写下划线，对齐 info_source.adapter_type 列）。 */
    public String wireCode() {
        return wireCode;
    }

    /** 大小写不敏感解析；null/空白/未知值返回 null（由校验器给出字段级提示，不在此抛出）。 */
    public static AdapterType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (AdapterType type : values()) {
            if (type.wireCode.equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }
}
