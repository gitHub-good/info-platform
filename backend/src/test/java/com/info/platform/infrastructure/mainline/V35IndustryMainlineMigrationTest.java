package com.info.platform.infrastructure.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V35 迁移语义集成测试（M27 T242/T243，方案 §4.1 DDL）：context 启动时 Flyway 已应用 V35——断言<b>结构面</b>（三表/列/唯一约束/
 * 索引在位，V31MarketTopMigrationTest 同款镜像断言面——迁移文件漂移由此暴露）。
 */
@SpringBootTest
@ActiveProfiles("test")
class V35IndustryMainlineMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    @Test
    void v35_structure_threeTablesPresent() {
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN"
                                        + " ('industry_market_snapshot','industry_mainline','industry_mainline_batch')"))
                .isEqualTo(3);
    }

    @Test
    void v35_structure_snapshotUniqueConstraintUpsertable() {
        // UNIQUE(row_type, dim_name, snapshot_date, market) 幂等键在位（V37 起含 market 维）：ON CONFLICT
        // 目标列集可 UPSERT
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM pragma_index_list('industry_market_snapshot')"
                                        + " WHERE \"unique\" = 1"))
                .isGreaterThanOrEqualTo(1);
        jdbcTemplate.update(
                "INSERT INTO industry_market_snapshot (row_type, dim_name, industry, snapshot_date,"
                        + " source, agg_method, created_at, updated_at) VALUES ('INDUSTRY','电子','电子',"
                        + "'2099-12-31','tencent-rank','TENCENT_DIRECT','2026-09-28T10:00:00Z',"
                        + "'2026-09-28T10:00:00Z')");
        jdbcTemplate.update(
                "INSERT INTO industry_market_snapshot (row_type, dim_name, industry, snapshot_date,"
                        + " pct_day, source, agg_method, created_at, updated_at) VALUES ('INDUSTRY','电子',"
                        + "'电子','2099-12-31', 1.5, 'tencent-rank','TENCENT_DIRECT','2026-09-28T10:01:00Z',"
                        + "'2026-09-28T10:01:00Z') ON CONFLICT(row_type, dim_name, snapshot_date, market)"
                        + " DO UPDATE SET pct_day = excluded.pct_day, updated_at = excluded.updated_at");
        Double pct =
                jdbcTemplate.queryForObject(
                        "SELECT pct_day FROM industry_market_snapshot WHERE snapshot_date ="
                                + " '2099-12-31'",
                        Double.class);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM industry_market_snapshot WHERE snapshot_date = '2099-12-31'"))
                .isEqualTo(1);
        assertThat(pct).isEqualTo(1.5);
        jdbcTemplate.update(
                "DELETE FROM industry_market_snapshot WHERE snapshot_date = '2099-12-31'");
    }

    @Test
    void v35_structure_mainlineVersionedUniquesAndIndexes() {
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM pragma_index_list('industry_mainline')"
                                        + " WHERE \"unique\" = 1"))
                .isEqualTo(2); // UNIQUE(rank_date, version, rank_no) + UNIQUE(rank_date, version,
        // industry)
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name ="
                                        + " 'idx_iml_date_ver'"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM pragma_index_list('industry_mainline_batch')"
                                        + " WHERE \"unique\" = 1"))
                .isEqualTo(1); // UNIQUE(rank_date, version)
    }
}
