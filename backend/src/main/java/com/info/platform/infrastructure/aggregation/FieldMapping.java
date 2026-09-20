package com.info.platform.infrastructure.aggregation;

import java.util.Objects;

/**
 * 单条字段映射配置：源字段 → 目标字段 + {@link Transform} 转换规则。
 *
 * <p>值对象，可由 {@link FieldMapper#loadMapping} 从 classpath JSON 加载，或运行时直接构造注入子类 adapter。
 */
public final class FieldMapping {

    private final String source;
    private final String target;
    private final Transform transform;

    public FieldMapping(String source, String target, Transform transform) {
        this.source = Objects.requireNonNull(source, "source 必填");
        this.target = Objects.requireNonNull(target, "target 必填");
        this.transform = transform == null ? Transform.NONE : transform;
    }

    /** transform 默认 {@link Transform#NONE}。 */
    public FieldMapping(String source, String target) {
        this(source, target, Transform.NONE);
    }

    public String source() {
        return source;
    }

    public String target() {
        return target;
    }

    public Transform transform() {
        return transform;
    }
}
