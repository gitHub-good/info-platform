package com.info.platform.domain.aggregation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 数据源缺失/异常事件实体（data_source_event 表，T16）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳）由基础设施层 {@code DataSourceEventRepositoryImpl} 经
 * {@link #reconstruct} 回填。
 *
 * <p>生命周期：{@code AbstractSourceAdapter} 模板降级路径命中（缺失/超时/异常/熔断）时 {@link #create} 新建并落库； 本表为追加型事件流水，仅
 * INSERT、无状态翻转，故无 {@code markXxx} 之类变更方法（与 anomaly_event 不同——后者有 pushed 翻转）。
 *
 * <p>对齐技术方案 §4.2 data_source_event DDL：无 version 列（追加型事件流水，无并发 UPDATE 竞争）。
 */
public class DataSourceEvent {

    private Long id;
    private final SourceCode sourceCode;
    private final DataSourceEventType eventType;
    private final Long subjectId;
    private final String detail;
    private Instant createdAt;
    private Instant updatedAt;

    private DataSourceEvent(
            Long id,
            SourceCode sourceCode,
            DataSourceEventType eventType,
            Long subjectId,
            String detail,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.sourceCode = sourceCode;
        this.eventType = eventType;
        this.subjectId = subjectId;
        this.detail = detail;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 构建新数据源事件（id/时间戳留空，落库后回填）。
     *
     * @param sourceCode 数据源标识（六类之一）
     * @param eventType 事件类型（缺失/超时/错误/限频）
     * @param subjectId 标的内部主键，允许为空（本批调用点均带，DDL 允许空以备无标的上下文）
     * @param detail 人读详情（如 {@code no-data} / {@code exhausted sourceCode=QUOTE} / {@code
     *     circuit-open}），允许为空
     */
    public static DataSourceEvent create(
            SourceCode sourceCode, DataSourceEventType eventType, Long subjectId, String detail) {
        Objects.requireNonNull(sourceCode, "sourceCode 必填");
        Objects.requireNonNull(eventType, "eventType 必填");
        return new DataSourceEvent(null, sourceCode, eventType, subjectId, detail, null, null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static DataSourceEvent reconstruct(
            Long id,
            SourceCode sourceCode,
            DataSourceEventType eventType,
            Long subjectId,
            String detail,
            Instant createdAt,
            Instant updatedAt) {
        return new DataSourceEvent(
                id, sourceCode, eventType, subjectId, detail, createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public SourceCode getSourceCode() {
        return sourceCode;
    }

    public DataSourceEventType getEventType() {
        return eventType;
    }

    public Optional<Long> getSubjectId() {
        return Optional.ofNullable(subjectId);
    }

    public Optional<String> getDetail() {
        return Optional.ofNullable(detail);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
