package com.info.platform.domain.aggregation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 标的实体（subject_master 主数据）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/version/时间戳）
 * 由基础设施层 {@code SubjectRepositoryImpl} 经 {@link #reconstruct} 回填。
 */
public class Subject {

    private Long id;
    private SubjectCode subjectCode;
    private Market market;
    private SubjectType subjectType;
    private String name;
    private Map<String, String> externalCodes;
    private String industry;
    private SubjectStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private Subject() {
    }

    /** 构建新标的（id/version/时间戳留空，落库后回填）。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 从持久化数据重建实体（基础设施层落库后回读时用）。 */
    public static Subject reconstruct(Long id, SubjectCode subjectCode, Market market, SubjectType subjectType,
                                      String name, Map<String, String> externalCodes, String industry,
                                      SubjectStatus status, long version, Instant createdAt, Instant updatedAt) {
        Subject s = new Subject();
        s.id = id;
        s.subjectCode = subjectCode;
        s.market = market;
        s.subjectType = subjectType;
        s.name = name;
        s.externalCodes = externalCodes;
        s.industry = industry;
        s.status = status;
        s.version = version;
        s.createdAt = createdAt;
        s.updatedAt = updatedAt;
        return s;
    }

    public Long getId() {
        return id;
    }

    public SubjectCode getSubjectCode() {
        return subjectCode;
    }

    public Market getMarket() {
        return market;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getExternalCodes() {
        return externalCodes;
    }

    public String getIndustry() {
        return industry;
    }

    public SubjectStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 改名（领域行为：改名需校验非空）。 */
    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        this.name = newName;
    }

    public static class Builder {
        private final Subject subject = new Subject();

        public Builder subjectCode(SubjectCode v) {
            subject.subjectCode = v;
            return this;
        }

        public Builder market(Market v) {
            subject.market = v;
            return this;
        }

        public Builder subjectType(SubjectType v) {
            subject.subjectType = v;
            return this;
        }

        public Builder name(String v) {
            subject.name = v;
            return this;
        }

        public Builder externalCodes(Map<String, String> v) {
            subject.externalCodes = v;
            return this;
        }

        public Builder industry(String v) {
            subject.industry = v;
            return this;
        }

        public Builder status(SubjectStatus v) {
            subject.status = v;
            return this;
        }

        public Subject build() {
            Objects.requireNonNull(subject.subjectCode, "subjectCode 必填");
            Objects.requireNonNull(subject.market, "market 必填");
            Objects.requireNonNull(subject.subjectType, "subjectType 必填");
            Objects.requireNonNull(subject.name, "name 必填");
            if (subject.status == null) {
                subject.status = SubjectStatus.ENABLED;
            }
            return subject;
        }
    }
}
