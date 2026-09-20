package com.info.platform.infrastructure.aggregation;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源原始取数结果（基础设施层内部传递用，承载子类 doFetch 返回的原始字段）。
 *
 * <p>{@code data} 为外部源原始字段名 → 值，未经字段映射；{@link AbstractSourceAdapter} 调 {@link FieldMapper}
 * 将其转换为规范字段名后包装为领域层 {@link com.info.platform.domain.aggregation.SourceResult}。 doFetch 返回 {@code
 * Optional.empty()} 表示源当日无数据（→ MISSING）。
 *
 * <p>不可变：data 防御性拷贝为不可变视图。
 */
public final class RawFetch {

    private final Map<String, Object> data;
    private final String source;
    private final Instant updatedAt;

    public RawFetch(Map<String, Object> data, String source, Instant updatedAt) {
        Objects.requireNonNull(data, "data 必填");
        Objects.requireNonNull(source, "source 必填");
        Objects.requireNonNull(updatedAt, "updatedAt 必填");
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.source = source;
        this.updatedAt = updatedAt;
    }

    public Map<String, Object> data() {
        return data;
    }

    public String source() {
        return source;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
