package com.info.platform.infrastructure.markettop;

import com.info.platform.application.markettop.IndustryMemberStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link IndustryMemberStore} 端口的 SQLite 实现（M21 T180）：覆盖率计数（与 FactorSnapshotRepositoryImpl 活跃口径同款）+
 * 「只补 NULL」幂等回填（方案 §4.1.2 冻结 SQL 语义——{@code WHERE subject_code=? AND industry IS NULL}，二轮重跑零写入）。
 */
@Repository
public class IndustryMemberStoreImpl implements IndustryMemberStore {

    private static final String COUNT_ACTIVE_SQL =
            """
            SELECT COUNT(*) FROM subject_master WHERE market = 'A_SHARE' AND status = 1
            """;

    private static final String COUNT_ACTIVE_WITH_INDUSTRY_SQL =
            """
            SELECT COUNT(*) FROM subject_master
             WHERE market = 'A_SHARE' AND status = 1
               AND industry IS NOT NULL AND TRIM(industry) <> ''
            """;

    /** 幂等回填：仅 NULL 行可写（已有行业不动——东财改类走 SUBJECT_SYNC 正常刷新路径，本通道不覆盖）。 */
    private static final String BACKFILL_IF_ABSENT_SQL =
            """
            UPDATE subject_master
               SET industry = ?, updated_at = ?, version = version + 1
             WHERE subject_code = ? AND industry IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndustryMemberStoreImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long countActiveAShares() {
        Long count = jdbcTemplate.queryForObject(COUNT_ACTIVE_SQL, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveASharesWithIndustry() {
        Long count = jdbcTemplate.queryForObject(COUNT_ACTIVE_WITH_INDUSTRY_SQL, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public int backfillIndustryIfAbsent(String subjectCode, String industry) {
        return jdbcTemplate.update(
                BACKFILL_IF_ABSENT_SQL, industry, java.time.Instant.now().toString(), subjectCode);
    }
}
