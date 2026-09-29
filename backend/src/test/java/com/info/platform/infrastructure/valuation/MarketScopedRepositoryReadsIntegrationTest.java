package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M29 T256 分市场仓储读集成测试（SQL 面对账——服务层单测全 Mock 不覆盖 SQL）：活跃名录/行业成员/H24 热度/交易日 序列/最近快照日的 market
 * 过滤语义（跨市场重名行业消歧 + A 股行不受港美股行污染）。远未来代码与日期隔离，逐场景物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketScopedRepositoryReadsIntegrationTest {

    @Autowired private FactorSnapshotRepository factorRepository;

    @Autowired private MarketDailySnapshotRepository marketRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM subject_master WHERE subject_code LIKE 'HK9%' OR subject_code LIKE 'US9%'");
        jdbcTemplate.update(
                "DELETE FROM industry_heat_snapshot WHERE market IN ('HK','US') AND snapshot_at LIKE '2099%'");
        jdbcTemplate.update(
                "DELETE FROM market_daily_snapshot WHERE snapshot_date >= '2099-01-01'");
    }

    private long insertSubject(String code, String market, int status, String industry) {
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, name, market, status, industry, subject_type, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, 'STOCK', '2099-01-01T00:00:00Z',"
                        + " '2099-01-01T00:00:00Z')",
                code,
                "标的" + code,
                market,
                status,
                industry);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM subject_master WHERE subject_code = ?", Long.class, code);
    }

    @Test
    void marketScopedSubjectsAndMembers_filterByMarketAndStatus() {
        long active = insertSubject("HK90001", "HK", 1, "银行");
        insertSubject("HK90002", "HK", 0, "保险"); // 美股收敛形态：status=0 不入池
        insertSubject("US90001", "US", 1, "银行"); // 跨市场重名行业（market 消歧）

        List<FactorSnapshotRepository.SubjectRef> hkSubjects =
                factorRepository.findActiveSubjects(Market.HK);
        assertThat(hkSubjects).extracting(FactorSnapshotRepository.SubjectRef::id).contains(active);
        assertThat(hkSubjects)
                .extracting(FactorSnapshotRepository.SubjectRef::code)
                .contains("HK90001")
                .doesNotContain("HK90002", "US90001"); // status=0 剔除；US 代码不混入

        assertThat(factorRepository.findIndustryMembers(Market.HK))
                .filteredOn(row -> row.code().startsWith("HK9"))
                .extracting(FactorSnapshotRepository.IndustryMemberRow::industry)
                .containsExactly("银行"); // F10 枚举原文直出（免 SW 映射）
    }

    @Test
    void marketScopedH24Heat_aShareRowsNotPollutedByHkusRows() {
        // A 股既有口径零回归守护：港美股热度行落库后 findH24Heat(A_SHARE) 仍只含 A 股行
        jdbcTemplate.update(
                "INSERT INTO industry_heat_snapshot (market, industry, window_type, heat_score,"
                        + " prev_score, delta_pct, news_count, event_count, basis, snapshot_at,"
                        + " created_at, updated_at) VALUES ('HK', '银行', 'H24', 999.0, 0, 0, 1, 0,"
                        + " 'heat-v1', '2099-01-01T00:00:00Z', '2099-01-01T00:00:00Z',"
                        + " '2099-01-01T00:00:00Z')");
        jdbcTemplate.update(
                "INSERT INTO industry_heat_snapshot (market, industry, window_type, heat_score,"
                        + " prev_score, delta_pct, news_count, event_count, basis, snapshot_at,"
                        + " created_at, updated_at) VALUES ('US', '银行', 'H24', 888.0, 0, 0, 1, 0,"
                        + " 'heat-v1', '2099-01-01T00:00:00Z', '2099-01-01T00:00:00Z',"
                        + " '2099-01-01T00:00:00Z')");

        assertThat(factorRepository.findH24Heat(Market.HK))
                .filteredOn(row -> row.industry().equals("银行"))
                .allSatisfy(row -> assertThat(row.heatScore()).isEqualTo(999.0));
        assertThat(factorRepository.findH24Heat(Market.US))
                .filteredOn(row -> row.industry().equals("银行"))
                .allSatisfy(row -> assertThat(row.heatScore()).isEqualTo(888.0));
        // A 股读面不含港美股行（同名「银行」不串值——M21 原口径恢复性守护）
        assertThat(factorRepository.findH24Heat(Market.A_SHARE))
                .filteredOn(row -> row.industry().equals("银行"))
                .allSatisfy(
                        row -> assertThat(row.heatScore()).isNotEqualTo(999.0).isNotEqualTo(888.0));
    }

    @Test
    void marketScopedTradingDatesAndLatestSnapshot_joinSubjectMarket() {
        long hk = insertSubject("HK90001", "HK", 1, null);
        insertSubject("US90001", "US", 1, null);
        jdbcTemplate.update(
                "INSERT INTO market_daily_snapshot (subject_id, snapshot_date, close_price, source,"
                        + " quote_time, created_at, updated_at) VALUES (?, '2099-03-01', 100.0, 'TEST',"
                        + " '2099', '2099-01-01T00:00:00Z', '2099-01-01T00:00:00Z')",
                hk);
        jdbcTemplate.update(
                "INSERT INTO market_daily_snapshot (subject_id, snapshot_date, close_price, source,"
                        + " quote_time, created_at, updated_at) VALUES (?, '2099-03-02', 101.0, 'TEST',"
                        + " '2099', '2099-01-01T00:00:00Z', '2099-01-01T00:00:00Z')",
                hk);

        assertThat(marketRepository.findTradingDates(Market.HK))
                .filteredOn(date -> date.startsWith("2099"))
                .containsExactly("2099-03-01", "2099-03-02");
        // US 无任何快照行 → 2099 序列为空（不与他市场混序）
        assertThat(marketRepository.findTradingDates(Market.US))
                .filteredOn(date -> date.startsWith("2099"))
                .isEmpty();
        assertThat(marketRepository.findLatestSnapshotDate(Market.HK)).contains("2099-03-02");
        assertThat(marketRepository.findLatestSnapshotDate(Market.US)).isEmpty();
    }
}
