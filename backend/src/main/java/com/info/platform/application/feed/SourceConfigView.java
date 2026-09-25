package com.info.platform.application.feed;

import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import java.util.Map;

/**
 * 源配置视图（{@code info_source.config} 的 REST 线格式，M13 T105）：编辑 Dialog 回填与卡片展示用。
 *
 * @param listPath json_api 条目数组点分路径（null = 根数组）
 * @param stripPrefix json_api 包装前缀（金十 {@code var newest=}）
 * @param stripSuffix json_api 包装后缀
 * @param itemMapping 字段映射（rss 缺省为空 = 引擎默认映射）
 * @param headers 外呼头白名单（User-Agent / Referer）
 * @param maxItems 单轮入库上限（null = 缺省 50）
 * @param pageSize 深翻每页条数（null = 缺省 20）
 * @param cursorType 游标类型线格式（ID / TIME / NONE）
 * @param cursorField 游标取值字段
 */
public record SourceConfigView(
        String listPath,
        String stripPrefix,
        String stripSuffix,
        List<ItemMappingView> itemMapping,
        Map<String, String> headers,
        Integer maxItems,
        Integer pageSize,
        String cursorType,
        String cursorField) {

    /** 单条字段映射视图。 */
    public record ItemMappingView(String source, String target, String transform) {}

    /** 领域对象 → 视图（游标类型取生效值，NONE 显式可见）。 */
    public static SourceConfigView from(SourceConfig config) {
        return new SourceConfigView(
                config.listPath(),
                config.stripPrefix(),
                config.stripSuffix(),
                config.mappings().stream()
                        .map(m -> new ItemMappingView(m.source(), m.target(), m.transform()))
                        .toList(),
                config.headers(),
                config.maxItems(),
                config.pageSize(),
                config.effectiveCursorType().name(),
                config.cursorField());
    }
}
