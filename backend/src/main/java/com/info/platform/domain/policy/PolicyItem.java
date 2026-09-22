package com.info.platform.domain.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 政策条目实体（policy_item，政策时事流主数据）。
 *
 * <p>领域层纯净：仅依赖 JDK 类型，可脱离容器单测、可移植。持久化字段（id/时间戳）由基础设施层 {@code PolicyRepositoryImpl} 经 {@link
 * #reconstruct} 回填。
 *
 * <p>字段语义（对齐 §4.2 DDL + §4.1.5 接口）：title 标题（含发文单位原文）/ source 来源标签 / publishedAt 发布日期 （gov.cn
 * 列表页仅日期粒度，故存 {@link LocalDate}）/ summary 摘要（列表页无，待详情页/T28 填，可空）/ relatedIndustries 关联行业标签数组（{@link
 * PolicyIndustryClassifier} 按标题关键词命中）/ aiTendency 倾向（T24 默认 {@link AiTendency#UNJUDGED}，T28 填）/
 * sourceUrl 详情 URL（去重键）。
 */
public class PolicyItem {

    private Long id;
    private String title;
    private String source;
    private LocalDate publishedAt;
    private String summary;
    private List<String> relatedIndustries;
    private AiTendency aiTendency;
    private String sourceUrl;
    private Instant createdAt;
    private Instant updatedAt;

    private PolicyItem() {}

    /** 构建新政策条目（id/时间戳留空，落库后回填）。aiTendency 默认未判（T28 才填）。 */
    public static PolicyItem create(
            String title,
            String source,
            LocalDate publishedAt,
            String summary,
            List<String> relatedIndustries,
            String sourceUrl) {
        Objects.requireNonNull(title, "title 必填");
        Objects.requireNonNull(source, "source 必填");
        Objects.requireNonNull(publishedAt, "publishedAt 必填");
        PolicyItem p = new PolicyItem();
        p.title = title;
        p.source = source;
        p.publishedAt = publishedAt;
        p.summary = summary;
        p.relatedIndustries =
                relatedIndustries == null ? List.of() : List.copyOf(relatedIndustries);
        p.aiTendency = AiTendency.UNJUDGED;
        p.sourceUrl = sourceUrl;
        return p;
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static PolicyItem reconstruct(
            Long id,
            String title,
            String source,
            LocalDate publishedAt,
            String summary,
            List<String> relatedIndustries,
            AiTendency aiTendency,
            String sourceUrl,
            Instant createdAt,
            Instant updatedAt) {
        PolicyItem p = new PolicyItem();
        p.id = id;
        p.title = title;
        p.source = source;
        p.publishedAt = publishedAt;
        p.summary = summary;
        p.relatedIndustries =
                relatedIndustries == null ? List.of() : List.copyOf(relatedIndustries);
        p.aiTendency = aiTendency;
        p.sourceUrl = sourceUrl;
        p.createdAt = createdAt;
        p.updatedAt = updatedAt;
        return p;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSource() {
        return source;
    }

    public LocalDate getPublishedAt() {
        return publishedAt;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getRelatedIndustries() {
        return relatedIndustries;
    }

    public AiTendency getAiTendency() {
        return aiTendency;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
