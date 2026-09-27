package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.markettop.MarketTopRepository;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopBatchRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopRankRow;
import com.info.platform.domain.markettop.MarketTopRepository.MarketTopVersion;
import com.info.platform.domain.markettop.MarketTopRepository.VersionSummary;
import com.info.platform.domain.markettop.RankDiffer.PrevSubject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MarketTopRepositoryImpl 集成测试（M21 T183，V31 两表）：追加式版本化（同日 version 递增、UNIQUE 兜底） / findLatest 语义 /
 * 昨日榜单 7 天回看窗 / 版本列表排序。夹具用远未来日期隔离，逐轮物理清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class MarketTopRepositoryImplTest {

    /** 远未来隔离日（不与业务日期碰撞）。 */
    private static final String DATE = "2099-12-31";

    private static final String PREV_DATE = "2099-12-30";

    @Autowired private MarketTopRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM market_top_rank WHERE rank_date >= '2099-01-01'");
        jdbcTemplate.update("DELETE FROM market_top_batch WHERE rank_date >= '2099-01-01'");
    }

    private static MarketTopRankRow rank(String date, int version, int rankNo, long subjectId) {
        return new MarketTopRankRow(
                date,
                version,
                rankNo,
                subjectId,
                "SH" + subjectId,
                "标的" + subjectId,
                60.0,
                61.0,
                99.0,
                true,
                "FULL",
                "LLM",
                "摘要",
                "{\"thesis\":\"论点\"}",
                3,
                "2099-12-29",
                null,
                "NEW",
                "mt-v1:...",
                "2099-12-31T10:00:00Z");
    }

    private static MarketTopBatchRow batch(String date, int version, boolean degraded) {
        return new MarketTopBatchRow(
                date,
                version,
                "DAILY",
                date,
                "{\"topSize\":2}",
                degraded,
                degraded ? "COST_CAP" : null,
                "[]",
                120_000L,
                10,
                "v1.0",
                "mt-v1:...",
                null,
                "2099-12-31T10:00:00Z");
    }

    @Test
    void insertAndFind_versionedAppend_latestWins() {
        assertThat(repository.maxVersion(DATE)).isZero();

        repository.insertVersion(
                batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1), rank(DATE, 1, 2, 2)));
        repository.insertVersion(
                batch(DATE, 2, true), List.of(rank(DATE, 2, 1, 2), rank(DATE, 2, 2, 3)));

        assertThat(repository.maxVersion(DATE)).isEqualTo(2);

        Optional<MarketTopVersion> latest = repository.findLatest(DATE);
        assertThat(latest).isPresent();
        assertThat(latest.get().batch().version()).isEqualTo(2);
        assertThat(latest.get().batch().degraded()).isTrue();
        assertThat(latest.get().items()).extracting(MarketTopRankRow::rankNo).containsExactly(1, 2);
        assertThat(latest.get().items().get(0).subjectId()).isEqualTo(2L);

        // 精确版本读取：v1 仍完整可读（追加不覆盖）
        Optional<MarketTopVersion> v1 = repository.find(DATE, 1);
        assertThat(v1).isPresent();
        assertThat(v1.get().items())
                .extracting(MarketTopRankRow::subjectId)
                .containsExactly(1L, 2L);
        assertThat(repository.find(DATE, 3)).isEmpty();
        assertThat(repository.find("2099-11-01", 1)).isEmpty();
    }

    @Test
    void findLatestAnyDate_returnsMaxDateMaxVersion() {
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 1)));
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 5)));

        Optional<MarketTopVersion> latest = repository.findLatestAnyDate();
        assertThat(latest).isPresent();
        assertThat(latest.get().batch().rankDate()).isEqualTo(DATE);
    }

    @Test
    void findPreviousTop_lookbackWithinSevenDays() {
        repository.insertVersion(
                batch(PREV_DATE, 1, false),
                List.of(rank(PREV_DATE, 1, 1, 7), rank(PREV_DATE, 1, 2, 8)));
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 9)));

        // 次日 diff：取 PREV_DATE 的 top（rank_no 升序）
        // 次日 diff：取最近有榜单日（DATE 2099-12-31 晚于 PREV_DATE，7 天窗内）的 top（rank_no 升序）
        List<PrevSubject> prev = repository.findPreviousTop("2100-01-01");
        assertThat(prev).extracting(PrevSubject::subjectId).containsExactly(9L);

        // 回看窗 7 天外无榜单 → 空
        assertThat(repository.findPreviousTop("2100-06-01")).isEmpty();
        assertThat(repository.findPreviousTop("2099-01-01")).isEmpty();
    }

    @Test
    void listVersions_sortedDateDescVersionDesc() {
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 1)));
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1)));
        repository.insertVersion(
                batch(DATE, 2, true), List.of(rank(DATE, 2, 1, 1), rank(DATE, 2, 2, 2)));

        List<VersionSummary> versions = repository.listVersions(null, 200);
        assertThat(versions).hasSizeGreaterThanOrEqualTo(3);
        assertThat(versions.get(0).rankDate()).isEqualTo(DATE);
        assertThat(versions.get(0).version()).isEqualTo(2);
        assertThat(versions.get(0).topSize()).isEqualTo(2);
        assertThat(versions.get(0).degraded()).isTrue();

        List<VersionSummary> byDate = repository.listVersions(DATE, 200);
        assertThat(byDate).allMatch(summary -> DATE.equals(summary.rankDate()));
        assertThat(byDate).extracting(VersionSummary::version).containsExactly(2, 1);
    }
}
