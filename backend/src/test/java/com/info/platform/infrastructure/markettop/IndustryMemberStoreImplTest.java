package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.markettop.IndustryMemberStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IndustryMemberStore 集成测试（M21 T180，方案 §4.1.2 冻结 SQL 语义）：覆盖率计数（活跃口径对齐 findActiveSubjects）/ 「只补
 * NULL」幂等（两连跑二轮零写入）/ 已有行业不被覆盖 / 未知代码零命中。SQLite 共享内存库直连断言（SubjectRepositorySyncPortTest
 * 同款；共享库隔离靠独立代码区段——计数断言用插入前后差值口径）。
 */
@SpringBootTest
@ActiveProfiles("test")
class IndustryMemberStoreImplTest {

    @Autowired private IndustryMemberStore store;

    @Autowired private JdbcTemplate jdbcTemplate;

    private void insertSubject(String code, String industry, int status) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO subject_master (subject_code, market, subject_type, name,"
                        + " external_codes, industry, status, missing_streak, created_at,"
                        + " updated_at, version) VALUES (?, 'A_SHARE', 1, ?, NULL, ?, ?, 0,"
                        + " '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z', 0)",
                code,
                "测试标的" + code,
                industry,
                status);
    }

    @Test
    void coverageCounts_activeAShareOrbitWithNonBlankIndustry() {
        // Arrange：启用有行业 / 启用无行业 / 启用空串行业 / 停用有行业（分母不含）
        long activeBefore = store.countActiveAShares();
        long withIndustryBefore = store.countActiveASharesWithIndustry();
        insertSubject("SH601981", "白酒Ⅱ", 1);
        insertSubject("SH601982", null, 1);
        insertSubject("SH601983", "  ", 1);
        insertSubject("SH601984", "银行Ⅱ", 0);

        // Act + Assert：+3 活跃（停用行不入分母），+1 有行业（null 与空白串不算分子）
        assertThat(store.countActiveAShares()).isEqualTo(activeBefore + 3);
        assertThat(store.countActiveASharesWithIndustry()).isEqualTo(withIndustryBefore + 1);
    }

    @Test
    void backfillIndustryIfAbsent_onlyNullRows_idempotentTwoRounds() {
        // Arrange：一行 NULL（回填目标）、一行已有行业（不得覆盖）
        insertSubject("SH601985", null, 1);
        insertSubject("SH601986", "白酒Ⅱ", 1);

        // Act：第一轮（NULL 行补填）+ 第二轮（幂等重跑）
        int first = store.backfillIndustryIfAbsent("SH601985", "证券Ⅱ");
        int second = store.backfillIndustryIfAbsent("SH601985", "证券Ⅱ");

        // Assert：一轮补 1、二轮 0（WHERE industry IS NULL 守卫——只补 NULL 幂等红线）
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(industryOf("SH601985")).isEqualTo("证券Ⅱ");
        // 已有行业行不被回填覆盖（东财改类走 SUBJECT_SYNC 正常刷新路径）
        assertThat(store.backfillIndustryIfAbsent("SH601986", "银行Ⅱ")).isZero();
        assertThat(industryOf("SH601986")).isEqualTo("白酒Ⅱ");
        // 未知代码零命中
        assertThat(store.backfillIndustryIfAbsent("SH999981", "银行Ⅱ")).isZero();
    }

    private String industryOf(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT industry FROM subject_master WHERE subject_code = ?", String.class, code);
    }
}
