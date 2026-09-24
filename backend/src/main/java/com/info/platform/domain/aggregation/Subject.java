package com.info.platform.domain.aggregation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 标的实体（subject_master 主数据）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/version/时间戳） 由基础设施层 {@code SubjectRepositoryImpl} 经
 * {@link #reconstruct} 回填。
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

    /**
     * 外部消失连续缺失计数（T51，V18 列 / ADR-0028）：该标的在所属市场桶的连续「本轮拉取成功但未出现」轮数。
     *
     * <p>机制列（与 status/version 同口径）：Builder 构建的新标的恒为 0；只有同步引擎经仓储端口推进/清零， 业务代码不读写。
     */
    private int missingStreak;

    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    private Subject() {}

    /** 构建新标的（id/version/时间戳留空，落库后回填）。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 从持久化数据重建实体（基础设施层落库后回读时用）；不走 {@link Builder} 预留类型守卫，读路径容忍历史/预留行。 */
    public static Subject reconstruct(
            Long id,
            SubjectCode subjectCode,
            Market market,
            SubjectType subjectType,
            String name,
            Map<String, String> externalCodes,
            String industry,
            SubjectStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        return reconstruct(
                id,
                subjectCode,
                market,
                subjectType,
                name,
                externalCodes,
                industry,
                status,
                version,
                createdAt,
                updatedAt,
                0);
    }

    /**
     * 从持久化数据重建实体（带 missing_streak，T51 V18 列回填）。
     *
     * <p>既有 11 参重载委托本方法并落 0（存量调用方零改动）；同步引擎读桶基线时用本重载取真实计数。
     */
    public static Subject reconstruct(
            Long id,
            SubjectCode subjectCode,
            Market market,
            SubjectType subjectType,
            String name,
            Map<String, String> externalCodes,
            String industry,
            SubjectStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            int missingStreak) {
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
        s.missingStreak = missingStreak;
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

    /** 外部消失连续缺失计数（V18 列，ADR-0028；仅同步引擎消费，见字段注释）。 */
    public int getMissingStreak() {
        return missingStreak;
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
            if (subject.subjectType.isReserved()) {
                // T31 扩展位守卫：预留类型（基金/债券）只开放类型体系与映射注册位，不允许创建标的数据。
                throw new IllegalArgumentException(
                        "标的类型预留未开放，不允许创建标的: "
                                + subject.subjectType.label()
                                + "(subjectType="
                                + subject.subjectType.code()
                                + ")");
            }
            if (subject.status == null) {
                subject.status = SubjectStatus.ENABLED;
            }
            return subject;
        }
    }
}
