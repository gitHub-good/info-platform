package com.info.platform.domain.feed;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 资讯源配置值对象（{@code info_source.config} JSON 文档的领域视图，M13 T100，方案 §4.3）。
 *
 * <p>领域层纯净：JSON 序列化/反序列化归基础设施层 {@code SourceConfigCodec}，本类只承载结构与缺省值； 结构校验归应用层
 * {@code SourceConfigValidator}（错误码 30072）。{@code transform} 保存线格式字符串（如 {@code to_iso_datetime}）， 由引擎侧经
 * {@code Transform.from} 解析——领域层不依赖基础设施层的枚举。
 *
 * @param listPath json_api 专用：条目数组点分路径（空/null = 根数组；rss/preset 忽略）
 * @param stripPrefix json_api 可选：JS 包装前缀（先剥后解析，金十 {@code var newest=}）
 * @param stripSuffix json_api 可选：JS 包装后缀（如 {@code ;}）
 * @param itemMapping 可选字段映射（rss 有默认映射；json_api 必填）：source/target/transform 白名单映射
 * @param headers 外呼头白名单（User-Agent/Referer；禁止存放密钥）
 * @param maxItems 单轮入库上限（null = 缺省 {@link #DEFAULT_MAX_ITEMS}，1~200）
 * @param pageSize 深翻补抓时每页条数（null = 缺省 {@link #DEFAULT_PAGE_SIZE}）
 * @param cursorType 游标类型（null = {@link CursorType#NONE}）
 * @param cursorField 游标取值字段（映射后目标字段名；cursorType ≠ NONE 时必填）
 */
public record SourceConfig(
        String listPath,
        String stripPrefix,
        String stripSuffix,
        List<ItemMapping> itemMapping,
        Map<String, String> headers,
        Integer maxItems,
        Integer pageSize,
        CursorType cursorType,
        String cursorField) {

    /** 单轮入库上限缺省（方案 §4.3：默认 50）。 */
    public static final int DEFAULT_MAX_ITEMS = 50;

    /** 深翻每页条数缺省（方案 §4.3：默认 20）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 单条字段映射：源字段 → 目标字段 + transform 线格式（白名单由校验器把关）。 */
    public record ItemMapping(String source, String target, String transform) {

        public ItemMapping {
            Objects.requireNonNull(source, "source 必填");
            Objects.requireNonNull(target, "target 必填");
        }
    }

    /** 空配置（缺省值全走 effective* 取值方法）。 */
    public static SourceConfig empty() {
        return new SourceConfig(
                null, null, null, List.of(), Map.of(), null, null, CursorType.NONE, null);
    }

    /** 生效单轮入库上限（null 取缺省）。 */
    public int effectiveMaxItems() {
        return maxItems == null ? DEFAULT_MAX_ITEMS : maxItems;
    }

    /** 生效深翻每页条数（null 取缺省）。 */
    public int effectivePageSize() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    /** 生效游标类型（null 视作 NONE）。 */
    public CursorType effectiveCursorType() {
        return cursorType == null ? CursorType.NONE : cursorType;
    }

    /** 条目映射（null 视作空表）。 */
    public List<ItemMapping> mappings() {
        return itemMapping == null ? List.of() : itemMapping;
    }

    /** 外呼头（null 视作空表；取数引擎与校验器共用空安全视图）。 */
    public Map<String, String> headers() {
        return headers == null ? Map.of() : headers;
    }
}
