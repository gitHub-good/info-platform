package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.SourceConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code info_source.config} JSON ↔ {@link SourceConfig} 编解码（M13 T100，基础设施层——领域不引 Jackson）。
 *
 * <p>无状态纯转换，可直接 {@code new} 使用；容错读：缺省字段回落 null（由 {@code effective*} 取缺省值）；游标类型缺失视作 NONE。
 * 写路径保持键序稳定（人读友好，页面 diff 可读）。
 */
public class SourceConfigCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析 config JSON。
     *
     * @throws IllegalStateException JSON 非法或结构不符（存量数据损坏时 fail-fast，不带病运行）
     */
    public SourceConfig parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return SourceConfig.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "info_source.config 非法 JSON: " + e.getOriginalMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalStateException("info_source.config 须为 JSON 对象");
        }
        List<SourceConfig.ItemMapping> mappings = new ArrayList<>();
        for (JsonNode entry : root.path("itemMapping")) {
            mappings.add(
                    new SourceConfig.ItemMapping(
                            entry.path("source").asText(null),
                            entry.path("target").asText(null),
                            entry.path("transform").asText("none")));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        root.path("headers")
                .fields()
                .forEachRemaining(field -> headers.put(field.getKey(), field.getValue().asText()));
        return new SourceConfig(
                root.path("listPath").asText(null),
                root.path("stripPrefix").asText(null),
                root.path("stripSuffix").asText(null),
                List.copyOf(mappings),
                Map.copyOf(headers),
                root.path("maxItems").isNumber() ? root.path("maxItems").asInt() : null,
                root.path("pageSize").isNumber() ? root.path("pageSize").asInt() : null,
                CursorType.from(root.path("cursorType").asText(null)),
                root.path("cursorField").asText(null));
    }

    /** 序列化（null 配置 → "{}"；键序固定）。 */
    public String write(SourceConfig config) {
        if (config == null) {
            return "{}";
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        putIfNotBlank(doc, "listPath", config.listPath());
        putIfNotBlank(doc, "stripPrefix", config.stripPrefix());
        putIfNotBlank(doc, "stripSuffix", config.stripSuffix());
        if (!config.mappings().isEmpty()) {
            List<Map<String, String>> mappings = new ArrayList<>();
            for (SourceConfig.ItemMapping mapping : config.mappings()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("source", mapping.source());
                entry.put("target", mapping.target());
                entry.put("transform", mapping.transform() == null ? "none" : mapping.transform());
                mappings.add(entry);
            }
            doc.put("itemMapping", mappings);
        }
        if (!config.headers().isEmpty()) {
            doc.put("headers", new LinkedHashMap<>(config.headers()));
        }
        if (config.maxItems() != null) {
            doc.put("maxItems", config.maxItems());
        }
        if (config.pageSize() != null) {
            doc.put("pageSize", config.pageSize());
        }
        doc.put("cursorType", config.effectiveCursorType().name());
        putIfNotBlank(doc, "cursorField", config.cursorField());
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "info_source.config 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    private static void putIfNotBlank(Map<String, Object> doc, String key, String value) {
        if (value != null && !value.isBlank()) {
            doc.put(key, value);
        }
    }
}
