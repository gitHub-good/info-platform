package com.info.platform.domain.aggregation;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 某数据源对某标的的取数结果值对象（领域层纯净，仅依赖 JDK 类型）。
 *
 * <p>不可变。{@code data} 为字段映射后的规范化字段集合（缓存命中的结果同样以此承载）。 MISSING/FAILED 时 {@code data} 为空 Map，{@code
 * updatedAt} 取降级时刻。
 *
 * <p>构造走静态工厂（{@link #ok}/{@link #missing}/{@link #failed}），隐藏构造细节、固化 status 语义。
 */
public final class SourceResult {

    private final SourceCode sourceCode;
    private final Long subjectId;
    private final Map<String, Object> data;
    private final String source;
    private final Instant updatedAt;
    private final SourceStatus status;

    private SourceResult(
            SourceCode sourceCode,
            Long subjectId,
            Map<String, Object> data,
            String source,
            Instant updatedAt,
            SourceStatus status) {
        this.sourceCode = sourceCode;
        this.subjectId = subjectId;
        this.data = data;
        this.source = source;
        this.updatedAt = updatedAt;
        this.status = status;
    }

    /** 取数成功且有数据。data 防御性拷贝为不可变视图。 */
    public static SourceResult ok(
            SourceCode sourceCode,
            Long subjectId,
            Map<String, Object> data,
            String source,
            Instant updatedAt) {
        requireKey(sourceCode, subjectId);
        Objects.requireNonNull(data, "data 必填");
        Objects.requireNonNull(source, "source 必填");
        Objects.requireNonNull(updatedAt, "updatedAt 必填");
        return new SourceResult(
                sourceCode, subjectId, copyData(data), source, updatedAt, SourceStatus.OK);
    }

    /** 数据源当日无数据 / 降级跳过（不阻断）。 */
    public static SourceResult missing(SourceCode sourceCode, Long subjectId, String source) {
        return degraded(sourceCode, subjectId, source, SourceStatus.MISSING);
    }

    /** 取数异常（仍不阻断其他分区）。 */
    public static SourceResult failed(SourceCode sourceCode, Long subjectId, String source) {
        return degraded(sourceCode, subjectId, source, SourceStatus.FAILED);
    }

    private static SourceResult degraded(
            SourceCode sourceCode, Long subjectId, String source, SourceStatus status) {
        requireKey(sourceCode, subjectId);
        String src = source == null ? sourceCode.name() : source;
        return new SourceResult(sourceCode, subjectId, Map.of(), src, Instant.now(), status);
    }

    private static void requireKey(SourceCode sourceCode, Long subjectId) {
        Objects.requireNonNull(sourceCode, "sourceCode 必填");
        Objects.requireNonNull(subjectId, "subjectId 必填");
    }

    private static Map<String, Object> copyData(Map<String, Object> data) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public SourceCode getSourceCode() {
        return sourceCode;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    /** 规范化字段集合（不可变）。MISSING/FAILED 时为空 Map。 */
    public Map<String, Object> getData() {
        return data;
    }

    public String getSource() {
        return source;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public SourceStatus getStatus() {
        return status;
    }
}
