package com.info.platform.interfaces.feed;

import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import java.util.Map;

/**
 * {@code info_source.config} 请求负载（M13 T105，接口层线格式 → 领域 VO）：新增/编辑共用； 缺省字段回落 null（领域 effective*
 * 取缺省值），游标类型大小写不敏感、未知值由校验器给字段级 30072 提示。M14 T110 增 {@code urlTemplate}（条目直链合成模板， 澎湃等无直链字段源）。
 *
 * @param cursorType 线格式（ID / TIME / NONE；空 = NONE）
 * @param urlTemplate 条目 URL 合成模板（可选，须含 {externalId} 占位——校验器把关）
 */
public record SourceConfigPayload(
        String listPath,
        String stripPrefix,
        String stripSuffix,
        List<ItemMappingPayload> itemMapping,
        Map<String, String> headers,
        Integer maxItems,
        Integer pageSize,
        String cursorType,
        String cursorField,
        String urlTemplate) {

    /** 单条字段映射负载。 */
    public record ItemMappingPayload(String source, String target, String transform) {}

    /** null 安全转领域 VO。 */
    public SourceConfig toDomain() {
        List<SourceConfig.ItemMapping> mappings =
                itemMapping == null
                        ? List.of()
                        : itemMapping.stream()
                                .map(
                                        m ->
                                                new SourceConfig.ItemMapping(
                                                        m.source(), m.target(), m.transform()))
                                .toList();
        return new SourceConfig(
                listPath,
                stripPrefix,
                stripSuffix,
                mappings,
                headers,
                maxItems,
                pageSize,
                CursorType.from(cursorType),
                cursorField,
                urlTemplate);
    }

    /** 空配置（rss 缺省映射形态）。 */
    public static SourceConfigPayload empty() {
        return new SourceConfigPayload(null, null, null, null, null, null, null, null, null, null);
    }
}
