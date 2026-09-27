package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.FactorSnapshotRow;
import com.info.platform.domain.valuation.ValuationEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * FactorSnapshotRepository 集成测试（T170，V30 表由 Flyway 建出）：UNIQUE(subject_id, snapshot_date) UPSERT
 * 当日重跑幂等、 输入投影四查询（活跃标的/事件窗/资讯回联窗/H24 热度）、coverage 计数与排名计数、最新快照检索。t170_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class FactorSnapshotRepositoryImplTest {

    private static final String DATE = "2026-09-22";

    @Autowired private FactorSnapshotRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, market, subject_type, name, status,"
                        + " created_at, updated_at) VALUES (?, 'A_SHARE', 1, ?, 1, ?, ?)",
                "SH990101",
                "t170_茅台",
                DATE,
                DATE);
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, market, subject_type, name, status,"
                        + " created_at, updated_at) VALUES (?, 'A_SHARE', 1, ?, 0, ?, ?)",
                "SH990102",
                "t170_停用",
                DATE,
                DATE);
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, market, subject_type, name, status,"
                        + " created_at, updated_at) VALUES (?, 'HK', 1, ?, 1, ?, ?)",
                "HK990103",
                "t170_港股",
                DATE,
                DATE);
        jdbcTemplate.update(
                "INSERT INTO subject_master (subject_code, market, subject_type, name, status,"
                        + " created_at, updated_at) VALUES (?, 'A_SHARE', 1, ?, 1, ?, ?)",
                "SH990104",
                "t170_平安",
                DATE,
                DATE);

        jdbcTemplate.update(
                "INSERT INTO info_source (source_code, name, category, adapter_type, endpoint,"
                        + " interval_minutes, is_preset, enabled, created_at, updated_at)"
                        + " VALUES ('t170_src', 't170源', '快讯', 'RSS',"
                        + " 'https://example.com/t170', 15, 1, 1, ?, ?)",
                DATE,
                DATE);
        long sourceId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM info_source WHERE source_code = 't170_src'", Long.class);

        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url,"
                        + " published_at, fetched_at, fingerprint, status, created_at,"
                        + " updated_at)"
                        + " VALUES (?, 't170_n1', 't170标题', 't170摘要',"
                        + " 'https://example.com/t170n1', '2026-09-21T02:00:00Z',"
                        + " '2026-09-21T02:00:00Z', 't170_fp1', 1, ?, ?)",
                sourceId,
                DATE,
                DATE);
        long newsId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE fingerprint = 't170_fp1'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, subjects, event_date, created_at, updated_at)"
                        + " VALUES (?, 'EARNINGS_FORECAST', 't170事件', '[\"食品饮料\"]',"
                        + " 'BULLISH', 'HIGH',"
                        + " '[{\"code\":\"SH990101\",\"name\":\"t170_茅台\",\"industry\":null}]',"
                        + " '2026-09-21', ?, ?)",
                newsId,
                DATE,
                DATE);

        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url,"
                        + " published_at, fetched_at, fingerprint, status, created_at,"
                        + " updated_at)"
                        + " VALUES (?, 't170_n2', 't170标题二', 't170摘要二',"
                        + " 'https://example.com/t170n2', '2026-09-20T08:00:00Z',"
                        + " '2026-09-20T08:00:00Z', 't170_fp2', 1, ?, ?)",
                sourceId,
                DATE,
                DATE);
        long newsId2 =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE fingerprint = 't170_fp2'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO news_analysis (news_id, l0_result, l1_status, main_category,"
                        + " sub_industry, matched_subjects, created_at, updated_at)"
                        + " VALUES (?, 'PASS', 'DONE', '电子', '银行',"
                        + " '[{\"code\":\"SH990101\",\"name\":\"t170_茅台\",\"industry\":\"食品饮料\"}]',"
                        + " ?, ?)",
                newsId2,
                DATE,
                DATE);
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url,"
                        + " published_at, fetched_at, fingerprint, status, created_at,"
                        + " updated_at)"
                        + " VALUES (?, 't170_n3', 't170标题三', 't170摘要三',"
                        + " 'https://example.com/t170n3', '2026-09-21T06:00:00Z',"
                        + " '2026-09-21T06:00:00Z', 't170_fp3', 1, ?, ?)",
                sourceId,
                DATE,
                DATE);
        long newsId3 =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE fingerprint = 't170_fp3'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO news_analysis (news_id, l0_result, l1_status, matched_subjects,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'PASS', 'PENDING',"
                        + " '[{\"code\":\"SH990104\",\"name\":\"t170_平安\",\"industry\":null}]',"
                        + " ?, ?)",
                newsId3,
                DATE,
                DATE);

        jdbcTemplate.update(
                "INSERT INTO industry_heat_snapshot (industry, window_type, heat_score, prev_score,"
                        + " delta_pct, news_count, event_count, basis, snapshot_at, created_at,"
                        + " updated_at) VALUES ('银行', 'H24', 812.4, 0, 100, 10, 2, 't170_basis',"
                        + " ?, ?, ?)",
                DATE,
                DATE,
                DATE);
        jdbcTemplate.update(
                "INSERT INTO industry_heat_snapshot (industry, window_type, heat_score, prev_score,"
                        + " delta_pct, news_count, event_count, basis, snapshot_at, created_at,"
                        + " updated_at) VALUES ('银行', 'D7', 999.0, 0, 100, 10, 2, 't170_basis',"
                        + " ?, ?, ?)",
                DATE,
                DATE,
                DATE);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM subject_factor_snapshot WHERE subject_id IN"
                        + " (SELECT id FROM subject_master WHERE subject_code LIKE 'S%9901%' OR"
                        + " subject_code LIKE 'HK9901%')");
        jdbcTemplate.update(
                "DELETE FROM market_daily_snapshot WHERE subject_id IN"
                        + " (SELECT id FROM subject_master WHERE subject_code LIKE 'S%9901%' OR"
                        + " subject_code LIKE 'HK9901%')");
        jdbcTemplate.update("DELETE FROM industry_heat_snapshot WHERE basis = 't170_basis'");
        jdbcTemplate.update(
                "DELETE FROM event_item WHERE news_id IN (SELECT id FROM news_item WHERE"
                        + " fingerprint LIKE 't170_fp%')");
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN (SELECT id FROM news_item WHERE"
                        + " fingerprint LIKE 't170_fp%')");
        jdbcTemplate.update("DELETE FROM news_item WHERE fingerprint LIKE 't170_fp%'");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code = 't170_src'");
        jdbcTemplate.update(
                "DELETE FROM subject_master WHERE subject_code LIKE 'S%9901%' OR subject_code"
                        + " LIKE 'HK9901%'");
    }

    private long subjectId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM subject_master WHERE subject_code = ?", Long.class, code);
    }

    @Test
    void findActiveSubjects_aShareEnabledOnly() {
        List<FactorSnapshotRepository.SubjectRef> subjects = repository.findActiveSubjects();

        assertThat(subjects)
                .anySatisfy(
                        s -> {
                            assertThat(s.code()).isEqualTo("SH990101");
                            assertThat(s.name()).isEqualTo("t170_茅台");
                        });
        assertThat(subjects).noneMatch(s -> s.code().equals("SH990102")); // 停用
        assertThat(subjects).noneMatch(s -> s.code().equals("HK990103")); // 非 A 股
        assertThat(repository.countActiveSubjects()).isEqualTo(subjects.size()); // 计数=清单同口径
        assertThat(subjects).extracting(FactorSnapshotRepository.SubjectRef::id).isSorted();
    }

    @Test
    void findEventsInWindow_boundedAndJsonParsed() {
        // 窗界 [from, to] 含两端（age ∈ [0, W) 由域层判定，仓储取宽窗 W2）
        List<FactorSnapshotRepository.EventRef> events =
                repository.findEventsInWindow("2026-08-23", "2026-09-22");

        assertThat(events).hasSize(1);
        FactorSnapshotRepository.EventRef ref = events.get(0);
        ValuationEvent event = ref.event();
        assertThat(event.eventId()).isPositive();
        assertThat(event.eventType()).isEqualTo(EventType.EARNINGS_FORECAST);
        assertThat(event.direction()).isEqualTo(Direction.BULLISH);
        assertThat(event.importance()).isEqualTo(Importance.HIGH);
        assertThat(event.eventDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
        assertThat(ref.subjectCodes()).containsExactly("SH990101");
        assertThat(ref.affectedIndustries()).containsExactly("食品饮料");

        assertThat(repository.findEventsInWindow("2026-09-22", "2026-09-22")).isEmpty();
    }

    @Test
    void findMatchedNewsInWindow_doneRowsWithSubjects() {
        // published_at ∈ [2026-08-23T16:00Z, 2026-09-22T16:00Z)（上海日界）；PENDING 行排除
        List<FactorSnapshotRepository.NewsLinkRow> links =
                repository.findMatchedNewsInWindow("2026-08-23T16:00:00Z", "2026-09-22T16:00:00Z");

        assertThat(links).hasSize(1);
        FactorSnapshotRepository.NewsLinkRow link = links.get(0);
        assertThat(link.subjectCodes()).containsExactly("SH990101");
        assertThat(link.mainCategory()).isEqualTo("电子");
        assertThat(link.subIndustry()).isEqualTo("银行");
        assertThat(link.publishedDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 20));

        assertThat(
                        repository.findMatchedNewsInWindow(
                                "2026-09-21T16:00:00Z", "2026-09-22T16:00:00Z"))
                .isEmpty();
    }

    @Test
    void findH24Heat_excludesD7Window() {
        List<com.info.platform.domain.valuation.HeatRow> rows = repository.findH24Heat();

        assertThat(rows).noneMatch(r -> r.heatScore() == 999.0); // D7 行不进
        assertThat(rows)
                .anySatisfy(
                        r -> {
                            assertThat(r.industry()).isEqualTo("银行");
                            assertThat(r.heatScore()).isEqualTo(812.4);
                        });
    }

    @Test
    void upsertAll_sameDayRerunOverwritesSingleRow() {
        long id = subjectId("SH990101");
        FactorSnapshotRow first = row(id, 10.0, 20.0, 30.0, 40.0, 50.0, 25.0, false, "[]");
        FactorSnapshotRow second =
                row(id, 11.0, 21.0, 31.0, 41.0, 51.0, 26.0, true, "[\"ST_RISK\"]");

        assertThat(repository.upsertAll(List.of(first))).isEqualTo(1);
        assertThat(repository.upsertAll(List.of(second))).isEqualTo(1);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_factor_snapshot WHERE subject_id = ? AND"
                                + " snapshot_date = ?",
                        Integer.class,
                        id,
                        DATE);
        assertThat(count).isEqualTo(1); // UNIQUE 幂等：当日重跑收敛单行
        Optional<FactorSnapshotRow> latest = repository.findLatestBySubject(id);
        assertThat(latest).isPresent();
        assertThat(latest.get().fCatalyst()).isEqualTo(11.0); // 后写覆盖
        assertThat(latest.get().breakthrough()).isTrue();
        assertThat(latest.get().dataFlagsJson()).isEqualTo("[\"ST_RISK\"]");
        assertThat(latest.get().weightBasis()).startsWith("vs-v1:");
    }

    @Test
    void upsertAll_carriesLastEventDate_sameDayOverwrite() {
        // M21 §4.1.6：last_event_date 随行落库；当日重跑同键覆盖（含置空——无事件日如实 NULL）
        long id = subjectId("SH990101");
        FactorSnapshotRow withEvent = row(id, 10.0, 0.0, 50.0, 100.0, 50.0, 20.0, false, "[]");
        FactorSnapshotRow withoutEvent =
                new FactorSnapshotRow(
                        id,
                        DATE,
                        0.0,
                        0.0,
                        50.0,
                        100.0,
                        50.0,
                        20.0,
                        false,
                        "{}",
                        "[]",
                        "vs-v1:…",
                        "2026-09-22T09:30:00Z",
                        null);

        repository.upsertAll(List.of(withEvent));
        assertThat(repository.findLatestBySubject(id).orElseThrow().lastEventDate())
                .isEqualTo("2026-09-20");

        repository.upsertAll(List.of(withoutEvent));
        assertThat(repository.findLatestBySubject(id).orElseThrow().lastEventDate()).isNull();
    }

    @Test
    void findIndustryMembers_activeAShareWithNonBlankIndustry() {
        // M21 六输入投影：A 股启用且 industry 非空行（东财板块原文——SW 映射在应用层）
        jdbcTemplate.update(
                "UPDATE subject_master SET industry = '白酒Ⅱ' WHERE subject_code = 'SH990101'");
        jdbcTemplate.update(
                "UPDATE subject_master SET industry = '银行Ⅱ' WHERE subject_code = 'SH990102'"); // 停用行
        jdbcTemplate.update(
                "UPDATE subject_master SET industry = '  ' WHERE subject_code = 'SH990104'"); // 空白串

        List<FactorSnapshotRepository.IndustryMemberRow> rows = repository.findIndustryMembers();

        assertThat(rows)
                .anySatisfy(
                        row -> {
                            assertThat(row.code()).isEqualTo("SH990101");
                            assertThat(row.industry()).isEqualTo("白酒Ⅱ");
                        });
        assertThat(rows).noneMatch(row -> "SH990102".equals(row.code())); // 停用不进投影
        assertThat(rows).noneMatch(row -> "SH990104".equals(row.code())); // 空白串不算成员
        assertThat(rows).noneMatch(row -> "HK990103".equals(row.code())); // 港股不进 A 股成员
    }

    @Test
    void coverageAndRankCounters() {
        long first = subjectId("SH990101");
        long second = subjectId("SH990104");
        repository.upsertAll(
                List.of(
                        row(
                                first,
                                90.0,
                                90.0,
                                90.0,
                                90.0,
                                50.0,
                                90.0,
                                true,
                                "[\"NO_ASSOC_INDUSTRY\"]"),
                        row(second, 10.0, 10.0, 10.0, 10.0, 50.0, 10.0, false, "[]")));

        assertThat(repository.findLatestSnapshotDate()).isEqualTo(Optional.of(DATE));
        assertThat(repository.countByDate(DATE)).isEqualTo(2);
        assertThat(repository.countScoreGreaterThan(DATE, 89.0)).isEqualTo(1); // 严格大于
        assertThat(repository.countScoreGreaterThan(DATE, 90.0)).isZero(); // 并列不计（rank = 严格大于 + 1）
        assertThat(repository.countFlagged(DATE, "NO_ASSOC_INDUSTRY")).isEqualTo(1);
        assertThat(repository.findLatestBySubject(subjectId("SH990102"))).isEmpty(); // 停用标的无快照
    }

    @Test
    void findLatestBySubject_picksMostRecentDate() {
        long id = subjectId("SH990101");
        repository.upsertAll(
                List.of(
                        row(id, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, false, "[]"),
                        row(id, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, false, "[]")));
        jdbcTemplate.update(
                "INSERT INTO subject_factor_snapshot (subject_id, snapshot_date, f_catalyst,"
                        + " f_conduction, f_fundamental, f_risk, f_valuation, total_score,"
                        + " breakthrough, factor_detail, data_flags, weight_basis, computed_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, '2026-09-21', 9, 9, 9, 9, 9, 9, 0, '{}', '[]', 'vs-v1',"
                        + " '2026-09-21T09:00:00Z', '2026-09-21T09:00:00Z',"
                        + " '2026-09-21T09:00:00Z')",
                id);

        Optional<FactorSnapshotRow> latest = repository.findLatestBySubject(id);

        assertThat(latest).isPresent();
        assertThat(latest.get().snapshotDate()).isEqualTo(DATE); // 最新快照日
        assertThat(latest.get().fCatalyst()).isEqualTo(2.0);
    }

    @Test
    void upsertAllIncremental_setsIncrementAt_andFullUpsertResetsToNull() {
        // M22 V33：增量覆盖写 increment_at；盘后全量 UPSERT 显式置 NULL 复位（value-score 双层时间戳依据）
        long id = subjectId("SH990101");
        FactorSnapshotRow incremental = row(id, 12.0, 22.0, 32.0, 42.0, 52.0, 27.0, false, "[]");
        String incrementAt = "2026-09-22T07:35:11Z";

        assertThat(repository.upsertAllIncremental(List.of(incremental), incrementAt)).isEqualTo(1);
        assertThat(incrementAtOf(id)).isEqualTo(incrementAt);

        // 全量重跑同键覆盖并复位增量标注
        FactorSnapshotRow full = row(id, 12.0, 22.0, 32.0, 42.0, 52.0, 27.0, false, "[]");
        assertThat(repository.upsertAll(List.of(full))).isEqualTo(1);
        assertThat(incrementAtOf(id)).isNull();

        // 增量再覆盖 → 值重新生效（当日多次增量，末次时刻为准）
        assertThat(repository.upsertAllIncremental(List.of(incremental), "2026-09-22T09:41:00Z"))
                .isEqualTo(1);
        assertThat(incrementAtOf(id)).isEqualTo("2026-09-22T09:41:00Z");
    }

    @Test
    void upsertAllIncremental_sameDayRerunOverwritesSingleRow() {
        long id = subjectId("SH990104");
        repository.upsertAllIncremental(
                List.of(row(id, 10.0, 20.0, 30.0, 40.0, 50.0, 25.0, false, "[]")),
                "2026-09-22T07:35:11Z");
        repository.upsertAllIncremental(
                List.of(row(id, 11.0, 21.0, 31.0, 41.0, 51.0, 26.0, true, "[]")),
                "2026-09-22T08:35:11Z");

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM subject_factor_snapshot WHERE subject_id = ? AND"
                                + " snapshot_date = ?",
                        Integer.class,
                        id,
                        DATE);
        assertThat(count).isEqualTo(1); // 既有 UNIQUE 幂等口径直用
        assertThat(incrementAtOf(id)).isEqualTo("2026-09-22T08:35:11Z");
    }

    @Test
    void findIncrementAt_presentOnlyForIncrementallyOverwrittenRow() {
        // M22 T192：increment 块依据——增量覆盖行非空 / 全量行与无行均 empty
        long incrementalId = subjectId("SH990101");
        long fullId = subjectId("SH990104");
        String incrementAt = "2026-09-22T07:35:11Z";
        repository.upsertAllIncremental(
                List.of(row(incrementalId, 10.0, 20.0, 30.0, 40.0, 50.0, 25.0, false, "[]")),
                incrementAt);
        repository.upsertAll(List.of(row(fullId, 10.0, 20.0, 30.0, 40.0, 50.0, 25.0, false, "[]")));

        assertThat(repository.findIncrementAt(incrementalId, DATE)).contains(incrementAt);
        assertThat(repository.findIncrementAt(fullId, DATE)).isEmpty();
        assertThat(repository.findIncrementAt(999999999L, DATE)).isEmpty();
    }

    private String incrementAtOf(long subjectId) {
        return jdbcTemplate.queryForObject(
                "SELECT increment_at FROM subject_factor_snapshot WHERE subject_id = ? AND"
                        + " snapshot_date = ?",
                String.class,
                subjectId,
                DATE);
    }

    private static FactorSnapshotRow row(
            long subjectId,
            double f1,
            double f2,
            double f3,
            double f4,
            double f5,
            double total,
            boolean breakthrough,
            String flags) {
        return new FactorSnapshotRow(
                subjectId,
                DATE,
                f1,
                f2,
                f3,
                f4,
                f5,
                total,
                breakthrough,
                "{\"catalyst\":{\"raw\":0,\"entries\":[]}}",
                flags,
                "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=60|50|80",
                "2026-09-22T09:30:00Z",
                "2026-09-20");
    }
}
