package com.info.platform.application.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.markettop.HitStatsService.DayStatView;
import com.info.platform.application.markettop.HitStatsService.HitStatsView;
import com.info.platform.application.markettop.HitStatsService.WindowView;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * HitStatsService 独立 SQL 回算对账集成测试（M22 T193，需求故事 5 场景 1）：造已知价格序列 + 榜单留痕（最大 version 日终语义 +
 * 停牌两形态），服务输出与方案 §4.4-⑦ 对账 SQL（独立复算上涨占比 + 窗口函数中位数）逐日精确一致；T+20 窗口未满 INSUFFICIENT 如实。远未来日期隔离，逐场景物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class HitStatsServiceIntegrationTest {

    @Autowired private HitStatsService service;

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM market_daily_snapshot WHERE snapshot_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM market_top_rank WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM market_top_batch WHERE rank_date >= '2099-01-01'");
    }

    // ---- 夹具 ----

    private record Subject(long id, String code, String name) {}

    private List<Subject> subjects(int count) {
        return jdbcTemplate.query(
                "SELECT id, subject_code, name FROM subject_master WHERE market = 'A_SHARE'"
                        + " AND status = 1 ORDER BY id ASC LIMIT ?",
                (rs, rowNum) ->
                        new Subject(
                                rs.getLong("id"),
                                rs.getString("subject_code"),
                                rs.getString("name")),
                count);
    }

    /** 落一版本榜单（batch + rank 两表直插——version 语义与生产写入同构）。 */
    private void insertVersion(
            String rankDate, int version, String triggerSource, List<Subject> members) {
        jdbcTemplate.update(
                "INSERT INTO market_top_batch (rank_date, version, trigger_source, snapshot_date,"
                        + " funnel_stats, degraded, dropped_subjects, dive_cost_micros,"
                        + " dive_llm_calls, basis, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, '{}', 0, '[]', 0, 0, 'mt-v1:…',"
                        + " '2099-01-01T10:00:00Z', '2099-01-01T10:00:00Z')",
                rankDate,
                version,
                triggerSource,
                rankDate);
        int rankNo = 1;
        for (Subject subject : members) {
            jdbcTemplate.update(
                    "INSERT INTO market_top_rank (rank_date, version, rank_no, subject_id,"
                            + " subject_code, subject_name, total_score, final_score, percentile,"
                            + " breakthrough, generation, dive_method, dive_summary, dive_detail,"
                            + " evidence_count, last_event_date, prev_rank, change_type, basis,"
                            + " computed_at, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 60.0, 60.0, 90.0, 0, 'FULL', 'LLM',"
                            + " '摘要', '{}', 3, ?, NULL, 'NEW', 'mt-v1:…',"
                            + " '2099-01-01T10:00:00Z', '2099-01-01T10:00:00Z',"
                            + " '2099-01-01T10:00:00Z')",
                    rankDate,
                    version,
                    rankNo++,
                    subject.id(),
                    subject.code(),
                    subject.name(),
                    rankDate);
        }
    }

    /** 落一行收盘价（close 为 null = 停牌有行无价；不插行 = 无价日缺行——两形态同计剔除）。 */
    private void insertClose(long subjectId, String date, Double close) {
        jdbcTemplate.update(
                "INSERT INTO market_daily_snapshot (subject_id, snapshot_date, close_price,"
                        + " pct_change, source, quote_time, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, 'TEST', '2099', '2099-01-01T10:00:00Z',"
                        + " '2099-01-01T10:00:00Z')",
                subjectId,
                date,
                close);
    }

    // ---- 对账 SQL（方案 §4.4-⑦——与实现完全独立的复算口径） ----

    /** 独立复算单榜单日 T+N 的上涨占比与有价样本数。 */
    private double[] sqlUpRatioAndPriced(String rankDate, int plusDays) {
        String targetDate = sqlTargetDate(rankDate, plusDays);
        Double upRatio =
                jdbcTemplate.queryForObject(
                        """
                        WITH top AS (SELECT r.subject_id FROM market_top_rank r WHERE r.rank_date = ?
                           AND r.version = (SELECT MAX(version) FROM market_top_batch WHERE rank_date = ?))
                        SELECT CAST(SUM(CASE WHEN p1.close_price > p0.close_price THEN 1 ELSE 0 END) AS REAL)
                               / COUNT(*)
                          FROM top
                          JOIN market_daily_snapshot p0 ON p0.subject_id = top.subject_id AND p0.snapshot_date = ?
                          JOIN market_daily_snapshot p1 ON p1.subject_id = top.subject_id AND p1.snapshot_date = ?
                         WHERE p0.close_price IS NOT NULL AND p1.close_price IS NOT NULL
                        """,
                        Double.class,
                        rankDate,
                        rankDate,
                        rankDate,
                        targetDate);
        Long priced =
                jdbcTemplate.queryForObject(
                        """
                        WITH top AS (SELECT r.subject_id FROM market_top_rank r WHERE r.rank_date = ?
                           AND r.version = (SELECT MAX(version) FROM market_top_batch WHERE rank_date = ?))
                        SELECT COUNT(*)
                          FROM top
                          JOIN market_daily_snapshot p0 ON p0.subject_id = top.subject_id AND p0.snapshot_date = ?
                          JOIN market_daily_snapshot p1 ON p1.subject_id = top.subject_id AND p1.snapshot_date = ?
                         WHERE p0.close_price IS NOT NULL AND p1.close_price IS NOT NULL
                        """,
                        Long.class,
                        rankDate,
                        rankDate,
                        rankDate,
                        targetDate);
        return new double[] {upRatio == null ? Double.NaN : upRatio, priced == null ? 0 : priced};
    }

    /** 独立复算中位数涨跌幅（SQLite 无 MEDIAN——窗口函数序插值）。 */
    private double sqlMedianPct(String rankDate, int plusDays) {
        String targetDate = sqlTargetDate(rankDate, plusDays);
        Double median =
                jdbcTemplate.queryForObject(
                        """
                        WITH top AS (SELECT r.subject_id FROM market_top_rank r WHERE r.rank_date = ?
                           AND r.version = (SELECT MAX(version) FROM market_top_batch WHERE rank_date = ?)),
                        pct AS (SELECT (p1.close_price - p0.close_price) * 100.0 / p0.close_price AS v
                                  FROM top
                                  JOIN market_daily_snapshot p0 ON p0.subject_id = top.subject_id AND p0.snapshot_date = ?
                                  JOIN market_daily_snapshot p1 ON p1.subject_id = top.subject_id AND p1.snapshot_date = ?
                                 WHERE p0.close_price IS NOT NULL AND p1.close_price IS NOT NULL)
                        SELECT AVG(v) FROM (
                          SELECT v, ROW_NUMBER() OVER (ORDER BY v) AS rn, COUNT(*) OVER () AS n FROM pct)
                         WHERE rn IN ((n + 1) / 2, (n + 2) / 2)
                        """,
                        Double.class,
                        rankDate,
                        rankDate,
                        rankDate,
                        targetDate);
        return median == null ? Double.NaN : median;
    }

    /** 目标交易日 = 榜单日后第 N 个快照日（独立推导——与服务实现零共享）。 */
    private String sqlTargetDate(String rankDate, int plusDays) {
        return jdbcTemplate.queryForObject(
                "SELECT snapshot_date FROM (SELECT snapshot_date, ROW_NUMBER() OVER (ORDER BY"
                        + " snapshot_date) AS rn FROM (SELECT DISTINCT snapshot_date FROM"
                        + " market_daily_snapshot WHERE snapshot_date > ?)) WHERE rn = ?",
                String.class,
                rankDate,
                plusDays);
    }

    // ---- 场景：maxVer 日终语义 + 停牌两形态 + 单窗对账 ----

    @Test
    void reconcile_maxVersionBasisAndSuspendedSamples_exactMatchWithSql() {
        List<Subject> pool = subjects(4);
        long s1 = pool.get(0).id();
        long s2 = pool.get(1).id();
        long s3 = pool.get(2).id();
        long s4 = pool.get(3).id();
        // v1 DAILY（s1/s2）+ v2 EVENT（s1~s4）——日终语义取最大 version v2
        insertVersion("2099-03-01", 1, "DAILY", List.of(pool.get(0), pool.get(1)));
        insertVersion("2099-03-01", 2, "EVENT", pool);
        // 基期：四标的 100；T+1：s1=110 上涨 / s2=90 下跌 / s3 有行无价（停牌）/ s4 缺行（无价日）
        insertClose(s1, "2099-03-01", 100.0);
        insertClose(s2, "2099-03-01", 100.0);
        insertClose(s3, "2099-03-01", 100.0);
        insertClose(s4, "2099-03-01", 100.0);
        insertClose(s1, "2099-03-02", 110.0);
        insertClose(s2, "2099-03-02", 90.0);
        insertClose(s3, "2099-03-02", null);
        // 序列延伸一日（T+5 窗推导原料——仍不足）
        insertClose(s1, "2099-03-03", 110.0);
        insertClose(s2, "2099-03-03", 90.0);

        HitStatsView view = service.stats();

        WindowView t1 =
                view.windows().stream()
                        .filter(w -> w.window().equals("T+1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(t1.days()).hasSize(1);
        DayStatView day = t1.days().get(0);
        // maxVer 口径：topSize=4（v2 EVENT 版本），停牌两形态剔除 2、有价 2
        assertThat(day.topSize()).isEqualTo(4);
        assertThat(day.pricedSamples()).isEqualTo(2);
        assertThat(day.excluded()).isEqualTo(2);
        // 对账：服务输出 == 独立 SQL 复算（占比/中位数精确一致）
        double[] sql = sqlUpRatioAndPriced("2099-03-01", 1);
        assertThat(day.upRatio()).isEqualTo(round3(sql[0]));
        assertThat(day.medianPctChg()).isEqualTo(round2(sqlMedianPct("2099-03-01", 1)));
        assertThat(day.upRatio()).isEqualTo(0.5); // 1/2（已知序列断言）
        assertThat(day.medianPctChg()).isEqualTo(0.0); // (+10 + −10)/2
        // T+5/T+20 窗口未满 INSUFFICIENT（首跑校准条款）
        assertThat(
                        view.windows().stream()
                                .filter(w -> w.window().equals("T+5"))
                                .findFirst()
                                .orElseThrow()
                                .days())
                .isEmpty();
        assertThat(
                        view.windows().stream()
                                .filter(w -> w.window().equals("T+20"))
                                .findFirst()
                                .orElseThrow()
                                .agg()
                                .status())
                .isEqualTo("INSUFFICIENT");
    }

    // ---- 场景：多榜单日池化 agg 对账 ----

    @Test
    void reconcile_multiDayPooledAgg_matchesSqlPerDay() {
        List<Subject> pool = subjects(2);
        long s1 = pool.get(0).id();
        long s2 = pool.get(1).id();
        // 6 个榜单日（2099-04-01~06）：每日 s1 +10% / s2 −10% —— 逐日占比恒 0.5、中位 0
        String[] dates = {
            "2099-04-01", "2099-04-02", "2099-04-03", "2099-04-04", "2099-04-05", "2099-04-06"
        };
        double s1Close = 100.0;
        double s2Close = 100.0;
        for (String date : dates) {
            insertClose(s1, date, s1Close);
            insertClose(s2, date, s2Close);
            insertVersion(date, 1, "DAILY", pool);
            s1Close *= 1.1;
            s2Close *= 0.9;
        }
        insertClose(s1, "2099-04-07", s1Close);
        insertClose(s2, "2099-04-07", s2Close);

        HitStatsView view = service.stats();

        WindowView t1 =
                view.windows().stream()
                        .filter(w -> w.window().equals("T+1"))
                        .findFirst()
                        .orElseThrow();
        assertThat(t1.days()).hasSize(6);
        for (DayStatView day : t1.days()) {
            double[] sql = sqlUpRatioAndPriced(day.rankDate(), 1);
            double sqlMedian = sqlMedianPct(day.rankDate(), 1);
            assertThat(day.pricedSamples()).isEqualTo((int) sql[1]);
            assertThat(day.upRatio()).isEqualTo(round3(sql[0]));
            assertThat(day.medianPctChg()).isEqualTo(round2(sqlMedian));
        }
        // 池化 agg OK（≥5 天）：总上涨/总有价 = 6/12；全样本中位 0
        assertThat(t1.agg().status()).isEqualTo("OK");
        assertThat(t1.agg().days()).isEqualTo(6);
        assertThat(t1.agg().upRatio()).isEqualTo(0.5);
        assertThat(t1.agg().medianPct()).isEqualTo(0.0);
        assertThat(view.asOf()).isEqualTo("2099-04-07");
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
