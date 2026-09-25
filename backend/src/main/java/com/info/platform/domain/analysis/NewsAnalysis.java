package com.info.platform.domain.analysis;

import java.time.Instant;
import java.util.Objects;

/**
 * 管道逐条状态与归类产物实体（{@code news_analysis} 表，M15 T120，ADR-0046 裁决 1：与 {@code news_item} 1:1 独立建表）。
 *
 * <p>领域层纯净（仅 JDK）。L0 建行走 {@link #newForL0}；L1 结果经 {@link #applyL1Result} 单向状态机（PENDING/FAILED →
 * DONE）落定，非法迁移拒绝（白名单校验在实体，对齐方案库 10「状态机」裁剪）；持久化的条件 UPDATE 守卫由仓储实现承担（幂等双保险）。
 */
public class NewsAnalysis {

    private final Long id;
    private final long newsId;
    private final L0Result l0Result;
    private final Long nearDupOf;
    private final String l0Detail;
    private final double importanceScore;
    private L1Status l1Status;
    private String mainCategory;
    private String rawMain;
    private String subIndustry;
    private Double confidence;
    private boolean lowConfidence;
    private String matchedSubjects;
    private int l1Attempts;
    private String l1PromptVersion;
    private Instant classifiedAt;
    private final L2Status l2Status;
    private final int l2Attempts;
    private final Instant createdAt;
    private Instant updatedAt;

    private NewsAnalysis(
            Long id,
            long newsId,
            L0Result l0Result,
            Long nearDupOf,
            String l0Detail,
            double importanceScore,
            L1Status l1Status,
            String mainCategory,
            String rawMain,
            String subIndustry,
            Double confidence,
            boolean lowConfidence,
            String matchedSubjects,
            int l1Attempts,
            String l1PromptVersion,
            Instant classifiedAt,
            L2Status l2Status,
            int l2Attempts,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.newsId = newsId;
        this.l0Result = Objects.requireNonNull(l0Result, "l0Result 必填");
        this.nearDupOf = nearDupOf;
        this.l0Detail = l0Detail;
        this.importanceScore = importanceScore;
        this.l1Status = Objects.requireNonNull(l1Status, "l1Status 必填");
        this.mainCategory = mainCategory;
        this.rawMain = rawMain;
        this.subIndustry = subIndustry;
        this.confidence = confidence;
        this.lowConfidence = lowConfidence;
        this.matchedSubjects = matchedSubjects;
        this.l1Attempts = l1Attempts;
        this.l1PromptVersion = l1PromptVersion;
        this.classifiedAt = classifiedAt;
        this.l2Status = l2Status;
        this.l2Attempts = l2Attempts;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * L0 建行（id/时间戳由仓储回填；NEAR_DUP 须带主条引用）。
     *
     * @param newsId news_item.id
     * @param l0Result 预筛结果
     * @param nearDupOf 近重复主条 news_id（仅 NEAR_DUP 必填，其余须为 null）
     * @param l0Detail 诊断信息（NOISE 命中规则名 / NEAR_DUP 海明距离+编辑距离）
     */
    public static NewsAnalysis newForL0(
            long newsId, L0Result l0Result, Long nearDupOf, String l0Detail) {
        if (l0Result == L0Result.NEAR_DUP) {
            if (nearDupOf == null || nearDupOf <= 0) {
                throw new IllegalArgumentException("NEAR_DUP 行必须引用主条 news_id");
            }
        } else if (nearDupOf != null) {
            throw new IllegalArgumentException("仅 NEAR_DUP 行可带 near_dup_of");
        }
        return new NewsAnalysis(
                null,
                newsId,
                l0Result,
                nearDupOf,
                l0Detail,
                0.0,
                L1Status.PENDING,
                null,
                null,
                null,
                null,
                false,
                null,
                0,
                null,
                null,
                L2Status.SKIP,
                0,
                null,
                null);
    }

    /** 从持久化数据重建实体（基础设施层回读时用）。 */
    public static NewsAnalysis reconstruct(
            Long id,
            long newsId,
            L0Result l0Result,
            Long nearDupOf,
            String l0Detail,
            double importanceScore,
            L1Status l1Status,
            String mainCategory,
            String rawMain,
            String subIndustry,
            Double confidence,
            boolean lowConfidence,
            String matchedSubjects,
            int l1Attempts,
            String l1PromptVersion,
            Instant classifiedAt,
            L2Status l2Status,
            int l2Attempts,
            Instant createdAt,
            Instant updatedAt) {
        return new NewsAnalysis(
                id,
                newsId,
                l0Result,
                nearDupOf,
                l0Detail,
                importanceScore,
                l1Status,
                mainCategory,
                rawMain,
                subIndustry,
                confidence,
                lowConfidence,
                matchedSubjects,
                l1Attempts,
                l1PromptVersion,
                classifiedAt,
                l2Status,
                l2Attempts,
                createdAt,
                updatedAt);
    }

    /**
     * L1 结果落定（单向状态机：PENDING/FAILED → DONE；DONE 再调用抛 {@code IllegalStateException}——幂等由仓储条件 UPDATE
     * 双保险，实体守卫白名单语义）。
     */
    public void applyL1Result(
            String mainCategory,
            String rawMain,
            String subIndustry,
            Double confidence,
            boolean lowConfidence,
            String matchedSubjects,
            String promptVersion,
            Instant classifiedAt) {
        if (l1Status == L1Status.DONE) {
            throw new IllegalStateException("L1 已完成，不允许重复归类: newsId=" + newsId);
        }
        this.l1Status = L1Status.DONE;
        this.mainCategory = mainCategory;
        this.rawMain = rawMain;
        this.subIndustry = subIndustry;
        this.confidence = confidence;
        this.lowConfidence = lowConfidence;
        this.matchedSubjects = matchedSubjects;
        this.l1PromptVersion = promptVersion;
        this.classifiedAt = classifiedAt;
    }

    /** L1 失败记账（attempts+1，状态置 FAILED；由仓储条件 UPDATE 承载幂等）。 */
    public void markL1Failed() {
        this.l1Status = L1Status.FAILED;
        this.l1Attempts++;
    }

    public Long getId() {
        return id;
    }

    public long getNewsId() {
        return newsId;
    }

    public L0Result getL0Result() {
        return l0Result;
    }

    public Long getNearDupOf() {
        return nearDupOf;
    }

    public String getL0Detail() {
        return l0Detail;
    }

    public double getImportanceScore() {
        return importanceScore;
    }

    public L1Status getL1Status() {
        return l1Status;
    }

    public String getMainCategory() {
        return mainCategory;
    }

    public String getRawMain() {
        return rawMain;
    }

    public String getSubIndustry() {
        return subIndustry;
    }

    public Double getConfidence() {
        return confidence;
    }

    public boolean isLowConfidence() {
        return lowConfidence;
    }

    public String getMatchedSubjects() {
        return matchedSubjects;
    }

    public int getL1Attempts() {
        return l1Attempts;
    }

    public String getL1PromptVersion() {
        return l1PromptVersion;
    }

    public Instant getClassifiedAt() {
        return classifiedAt;
    }

    public L2Status getL2Status() {
        return l2Status;
    }

    public int getL2Attempts() {
        return l2Attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
