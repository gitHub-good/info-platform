package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MarketDailySnapshotRepository 集成测试（T170，V30 表）：UNIQUE(subject_id, snapshot_date) UPSERT 当日重跑覆盖、
 * 估值缺数列 NULL 保留、按日取数 Map（F5 投影）、按日计数（coverage marketDataRows 口径）。t170_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketDailySnapshotRepositoryImplTest {

    private static final String DATE = "2026-09-22";

    @Autowired private MarketDailySnapshotRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private long subjectId;

    @BeforeEach
    void seedSubject() {
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, market, subject_type, name, status,"
                        + " created_at, updated_at) VALUES ('SH990201', 'A_SHARE', 1, 't170行情',"
                        + " 1, ?, ?)",
                DATE,
                DATE);
        subjectId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM subject_master WHERE subject_code = 'SH990201'",
                        Long.class);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM market_daily_snapshot WHERE subject_id = ?", subjectId);
        jdbcTemplate.update("DELETE FROM subject_master WHERE subject_code = 'SH990201'");
    }

    private MarketDailyRow row(Double pe, Double pb) {
        return new MarketDailyRow(
                subjectId,
                DATE,
                1237.0,
                -1.14,
                0.25,
                2.0,
                31239.0,
                pe,
                pb,
                "tencent",
                "20260922161403");
    }

    @Test
    void upsertAll_sameDayRerunSingleRowOverwritten() {
        assertThat(repository.upsertAll(List.of(row(17.37, 6.15)))).isEqualTo(1);
        assertThat(repository.upsertAll(List.of(row(18.0, null)))).isEqualTo(1);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM market_daily_snapshot WHERE subject_id = ?",
                        Integer.class,
                        subjectId);
        assertThat(count).isEqualTo(1); // UNIQUE 幂等收敛单行
        Map<Long, MarketDailyRow> byDate = repository.findByDate(DATE);
        assertThat(byDate).containsKey(subjectId);
        MarketDailyRow stored = byDate.get(subjectId);
        assertThat(stored.peTtm()).isEqualTo(18.0); // 后写覆盖
        assertThat(stored.pb()).isNull(); // 缺数 NULL 保留
        assertThat(stored.closePrice()).isEqualTo(1237.0);
        assertThat(stored.quoteTime()).isEqualTo("20260922161403");
    }

    @Test
    void findByDate_scopedToRequestedDate() {
        repository.upsertAll(List.of(row(10.0, 1.0)));
        jdbcTemplate.update(
                "INSERT INTO market_daily_snapshot (subject_id, snapshot_date, close_price,"
                        + " pct_change, turnover_rate, amplitude, volume, pe_ttm, pb, source,"
                        + " created_at, updated_at)"
                        + " VALUES (?, '2026-09-21', 1, 1, 1, 1, 1, 1, 1, 'tencent', ?, ?)",
                subjectId,
                DATE,
                DATE);

        assertThat(repository.findByDate(DATE)).hasSize(1);
        assertThat(repository.findByDate("2026-09-21")).hasSize(1);
        assertThat(repository.findByDate("2026-09-20")).isEmpty();
        assertThat(repository.countByDate(DATE)).isEqualTo(1);
    }

    @Test
    void upsertAll_emptyListNoOp() {
        assertThat(repository.upsertAll(List.of())).isZero();
        assertThat(repository.countByDate(DATE)).isZero();
    }
}
