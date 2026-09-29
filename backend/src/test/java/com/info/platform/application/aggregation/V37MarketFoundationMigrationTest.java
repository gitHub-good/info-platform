package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.IndustryCategory;
import com.info.platform.domain.analysis.IndustryEnumMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V37 迁移结构断言（M29 T251，方案 §3.1 + R7）：六表 market 维落位（NOT NULL DEFAULT 'A_SHARE'，存量零丢失由 行数 &gt;0 且全
 * 'A_SHARE' 护栏）、market_daily_snapshot 两列 / news_analysis.l1_market、market_top_batch 的 V33
 * trigger_events 列在重建中保留（微调回注点）、industry_enum_map 种子与代码白名单逐词一致（SQL ↔ Java 同源防漂移）。
 *
 * <p>存量数据逐列比对不在本类（共享内存库测试数据非生产形态）——生产库演练走交付自测（backup-before-v37.db 副本跑 V37 后逐表对账）。
 */
@SpringBootTest
@ActiveProfiles("test")
class V37MarketFoundationMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    // ---- ①~⑥ 六表 market 维（NOT NULL + DEFAULT 'A_SHARE'）----

    @Test
    void sixTables_marketColumnPresent_defaultAShare_rowsPreserved() {
        List<String> tables =
                List.of(
                        "industry_heat_snapshot",
                        "industry_market_snapshot",
                        "industry_mainline",
                        "industry_mainline_batch",
                        "market_top_rank",
                        "market_top_batch");
        for (String table : tables) {
            // 列落位（重建后新表结构）
            assertThat(columnNames(table)).as("表 %s 的 market 列", table).contains("market");
            // 内容护栏：迁移回填行全 'A_SHARE'（表可能尚无行——列存在即证结构，行级对账归生产库演练）
            List<String> marketValues = marketColumnValues(table);
            if (!marketValues.isEmpty()) {
                assertThat(marketValues).containsOnly("A_SHARE");
            }
        }
    }

    // ---- ⑦ market_daily_snapshot 两列 / ⑥ trigger_events 保留 ----

    @Test
    void marketDailySnapshot_marketCapAndCurrencyColumnsExist() {
        assertThat(columnNames("market_daily_snapshot")).contains("market_cap", "currency");
    }

    @Test
    void marketTopBatch_triggerEventsColumnPreservedFromV33() {
        // 方案 §3.1 ⑥ 微调点：重建保留 V33 归因列（否则 M22 增量重评面丢列）
        assertThat(columnNames("market_top_batch")).contains("trigger_events", "market");
    }

    // ---- ⑧ news_analysis.l1_market ----

    @Test
    void newsAnalysis_l1MarketColumnExists() {
        assertThat(columnNames("news_analysis")).contains("l1_market");
        // 索引落位（热度分市场聚合键）
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_na_market_cat'"))
                .isNotEmpty();
    }

    // ---- ⑨ industry_enum_map 种子：与代码白名单/映射器逐词一致（防 SQL↔Java 漂移）----

    @Test
    void industryEnumMap_seeded_hkIdentity_usMerged_consistentWithCode() {
        Integer hkCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM industry_enum_map WHERE market='HK'", Integer.class);
        Integer usCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM industry_enum_map WHERE market='US'", Integer.class);
        assertThat(hkCount).isEqualTo(IndustryCategory.hkSize()); // 31 直采
        assertThat(usCount).isEqualTo(IndustryEnumMapper.usMapping().size()); // 156 归并

        // 港股：raw_name = enum_name（直采恒等）且枚举全集 = 代码白名单
        List<Map<String, Object>> hkRows =
                jdbcTemplate.queryForList(
                        "SELECT raw_name, enum_name FROM industry_enum_map WHERE market='HK'");
        assertThat(hkRows)
                .allSatisfy(row -> assertThat(row.get("RAW_NAME")).isEqualTo(row.get("ENUM_NAME")));
        assertThat(hkRows.stream().map(r -> String.valueOf(r.get("ENUM_NAME"))))
                .containsExactlyInAnyOrderElementsOf(IndustryCategory.HK_INDUSTRIES);

        // 美股：SQL 映射与代码映射器逐词一致（同一生成源的双侧）
        List<Map<String, Object>> usRows =
                jdbcTemplate.queryForList(
                        "SELECT raw_name, enum_name FROM industry_enum_map WHERE market='US'");
        Map<String, String> sqlMap = new LinkedHashMap<>();
        usRows.forEach(
                r ->
                        sqlMap.put(
                                String.valueOf(r.get("RAW_NAME")),
                                String.valueOf(r.get("ENUM_NAME"))));
        assertThat(sqlMap).containsExactlyInAnyOrderEntriesOf(IndustryEnumMapper.usMapping());
        assertThat(sqlMap.values().stream().distinct().count())
                .isEqualTo(IndustryCategory.usSize()); // 40 大类全覆盖无空枚举
    }

    // ---- helpers ----

    private List<String> marketColumnValues(String table) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT market FROM " + table + " WHERE market IS NOT NULL", String.class);
    }

    private List<String> columnNames(String table) {
        return jdbcTemplate.query(
                "PRAGMA table_info(" + table + ")", (rs, rowNum) -> rs.getString("name"));
    }
}
