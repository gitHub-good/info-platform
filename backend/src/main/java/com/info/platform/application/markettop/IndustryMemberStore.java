package com.info.platform.application.markettop;

/**
 * 行业成员存量读写端口（M21 T180）：{@code subject_master.industry} 的覆盖率预检与「只补 NULL」幂等回填。
 *
 * <p>端口在应用层、实现在基础设施层（{@code
 * infrastructure.markettop.IndustryMemberStoreImpl}，JdbcTemplate）——QuoteBatchClient 同款分层；回填服务不感知
 * SQL。
 */
public interface IndustryMemberStore {

    /** A 股启用标的数（与 FactorSnapshotRepository.findActiveSubjects 同口径——覆盖率分母）。 */
    long countActiveAShares();

    /** A 股启用且 industry 非空的标的数（覆盖率分子）。 */
    long countActiveASharesWithIndustry();

    /**
     * 幂等回填单行：仅当该标的 industry 为 NULL 时写入（{@code UPDATE ... WHERE subject_code=? AND industry IS
     * NULL}）。
     *
     * @return 1 = 补填成功；0 = 行不存在 / 行业已非空（二轮重跑自然零写入——幂等红线）
     */
    int backfillIndustryIfAbsent(String subjectCode, String industry);
}
