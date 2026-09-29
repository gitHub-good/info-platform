package com.info.platform.infrastructure.markettop;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
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
                Market.A_SHARE,
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
                Market.A_SHARE,
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
        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isZero();

        repository.insertVersion(
                batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1), rank(DATE, 1, 2, 2)));
        repository.insertVersion(
                batch(DATE, 2, true), List.of(rank(DATE, 2, 1, 2), rank(DATE, 2, 2, 3)));

        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isEqualTo(2);

        Optional<MarketTopVersion> latest = repository.findLatest(DATE, Market.A_SHARE);
        assertThat(latest).isPresent();
        assertThat(latest.get().batch().version()).isEqualTo(2);
        assertThat(latest.get().batch().degraded()).isTrue();
        assertThat(latest.get().items()).extracting(MarketTopRankRow::rankNo).containsExactly(1, 2);
        assertThat(latest.get().items().get(0).subjectId()).isEqualTo(2L);

        // 精确版本读取：v1 仍完整可读（追加不覆盖）
        Optional<MarketTopVersion> v1 = repository.find(DATE, 1, Market.A_SHARE);
        assertThat(v1).isPresent();
        assertThat(v1.get().items())
                .extracting(MarketTopRankRow::subjectId)
                .containsExactly(1L, 2L);
        assertThat(repository.find(DATE, 3, Market.A_SHARE)).isEmpty();
        assertThat(repository.find("2099-11-01", 1, Market.A_SHARE)).isEmpty();
    }

    // ---- M22 T191：EVENT 归因列 + insertVersion 唯一冲突重试一次（互斥层③）----

    @Test
    void insertVersion_persistsTriggerEventsColumn() {
        MarketTopBatchRow eventBatch =
                new MarketTopBatchRow(
                        Market.A_SHARE,
                        DATE,
                        1,
                        "EVENT",
                        DATE,
                        "{\"topSize\":2,\"source\":\"incremental\"}",
                        false,
                        null,
                        "[]",
                        0L,
                        1,
                        "v1.0",
                        "mt-v1:...",
                        "[{\"eventId\":101,\"summary\":\"签订重大合同\",\"importance\":\"HIGH\"}]",
                        "2099-12-31T10:00:00Z");
        repository.insertVersion(eventBatch, List.of(rank(DATE, 1, 1, 1)));

        String triggerEvents =
                jdbcTemplate.queryForObject(
                        "SELECT trigger_events FROM market_top_batch WHERE rank_date = ? AND version = 1",
                        String.class,
                        DATE);
        assertThat(triggerEvents).contains("\"eventId\":101").contains("签订重大合同");
    }

    @Test
    void insertVersion_uniqueConflict_retriesOnceWithNextVersion() {
        repository.insertVersion(
                batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1), rank(DATE, 1, 2, 2)));

        // 模拟竞态：以过期 version=1 再插（DAILY 起跑与联动交叠的缝隙——重取 maxVersion+1 重试一次）
        int inserted =
                repository.insertVersion(
                        batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 5), rank(DATE, 1, 2, 6)));

        assertThat(inserted).isEqualTo(2);
        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isEqualTo(2);
        // v2 完整落库（batch + ranks 同事务原子）
        Optional<MarketTopVersion> v2 = repository.find(DATE, 2, Market.A_SHARE);
        assertThat(v2).isPresent();
        assertThat(v2.get().items())
                .extracting(MarketTopRankRow::subjectId)
                .containsExactly(5L, 6L);
        // v1 原样保留（追加不覆盖）
        assertThat(repository.find(DATE, 1, Market.A_SHARE)).isPresent();
    }

    @Test
    void findLatestAnyDate_returnsMaxDateMaxVersion() {
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 1)));
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 5)));

        Optional<MarketTopVersion> latest = repository.findLatestAnyDate(Market.A_SHARE);
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
        List<PrevSubject> prev = repository.findPreviousTop("2100-01-01", Market.A_SHARE);
        assertThat(prev).extracting(PrevSubject::subjectId).containsExactly(9L);

        // 回看窗 7 天外无榜单 → 空
        assertThat(repository.findPreviousTop("2100-06-01", Market.A_SHARE)).isEmpty();
        assertThat(repository.findPreviousTop("2099-01-01", Market.A_SHARE)).isEmpty();
    }

    @Test
    void listVersions_sortedDateDescVersionDesc() {
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 1)));
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1)));
        repository.insertVersion(
                batch(DATE, 2, true), List.of(rank(DATE, 2, 1, 1), rank(DATE, 2, 2, 2)));

        List<VersionSummary> versions = repository.listVersions(null, Market.A_SHARE, 200);
        assertThat(versions).hasSizeGreaterThanOrEqualTo(3);
        assertThat(versions.get(0).rankDate()).isEqualTo(DATE);
        assertThat(versions.get(0).version()).isEqualTo(2);
        assertThat(versions.get(0).topSize()).isEqualTo(2);
        assertThat(versions.get(0).degraded()).isTrue();

        List<VersionSummary> byDate = repository.listVersions(DATE, Market.A_SHARE, 200);
        assertThat(byDate).allMatch(summary -> DATE.equals(summary.rankDate()));
        assertThat(byDate).extracting(VersionSummary::version).containsExactly(2, 1);
    }

    // ---- M22：findLatestEventVersion（页头「最近增量重评」）+ listTopByMaxVersion（hits-v1 回算原料） ----

    @Test
    void findLatestEventVersion_latestEventBatchOfDate() {
        repository.insertVersion(batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1)));
        jdbcTemplate.update(
                "UPDATE market_top_batch SET trigger_source = 'EVENT', trigger_events ="
                        + " '[{\"eventId\":4821,\"summary\":\"业绩预增\",\"importance\":\"HIGH\"}]'"
                        + " WHERE rank_date = ? AND version = 1",
                DATE);
        repository.insertVersion(
                eventBatch(
                        DATE,
                        2,
                        "[{\"eventId\":4822,\"summary\":\"行业政策\",\"importance\":\"HIGH\"}]"),
                List.of(rank(DATE, 2, 1, 1)));

        Optional<MarketTopRepository.EventVersion> found =
                repository.findLatestEventVersion(DATE, Market.A_SHARE);

        assertThat(found).isPresent();
        assertThat(found.get().version()).isEqualTo(2);
        assertThat(found.get().createdAtIso()).isNotBlank();
        assertThat(found.get().triggerEventsJson()).contains("4822");
        // 无 EVENT 版本日（DAILY 也在册）→ empty
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 1)));
        assertThat(repository.findLatestEventVersion(PREV_DATE, Market.A_SHARE)).isEmpty();
    }

    @Test
    void listTopByMaxVersion_maxVersionRowsOnlyAcrossDates() {
        // DATE：v1 两行 / v2 一行（EVENT 最大版本日终语义）；PREV_DATE：v1 一行
        repository.insertVersion(
                batch(DATE, 1, false), List.of(rank(DATE, 1, 1, 1), rank(DATE, 1, 2, 2)));
        repository.insertVersion(eventBatch(DATE, 2, "[]"), List.of(rank(DATE, 2, 1, 3)));
        repository.insertVersion(batch(PREV_DATE, 1, false), List.of(rank(PREV_DATE, 1, 1, 4)));

        List<MarketTopRepository.RankedSubject> tops =
                repository.listTopByMaxVersion(Market.A_SHARE);

        // 各日仅最大 version 行；rank_date 降序、rank_no 升序
        assertThat(tops)
                .filteredOn(top -> DATE.equals(top.rankDate()))
                .extracting(MarketTopRepository.RankedSubject::subjectId)
                .containsExactly(3L); // v2（EVENT）——v1 两行不计入
        assertThat(tops)
                .filteredOn(top -> PREV_DATE.equals(top.rankDate()))
                .extracting(MarketTopRepository.RankedSubject::subjectId)
                .containsExactly(4L);
        assertThat(tops.get(0).rankDate()).isEqualTo(DATE); // DESC
    }

    // ---- M29 T256：同日三市场版本共存（UNIQUE(rank_date, version, market)——分市场独立榜单不混榜） ----

    @Test
    void sameDate_threeMarkets_versionsCoexistIsolated() {
        // 同日三市场各 v1（market 维隔离——A 股既有追加语义不变，港美股独立递增）
        for (Market market : List.of(Market.A_SHARE, Market.HK, Market.US)) {
            repository.insertVersion(
                    marketBatch(market, DATE, 1), List.of(marketRank(market, DATE, 1, 1, 91)));
        }
        // 各市场 maxVersion 独立：HK 再追加 v2，不影响 A 股/美股
        repository.insertVersion(
                marketBatch(Market.HK, DATE, 2), List.of(marketRank(Market.HK, DATE, 2, 1, 92)));

        assertThat(repository.maxVersion(DATE, Market.A_SHARE)).isEqualTo(1);
        assertThat(repository.maxVersion(DATE, Market.HK)).isEqualTo(2);
        assertThat(repository.maxVersion(DATE, Market.US)).isEqualTo(1);

        // findLatest 市场内最大版本（不混榜）
        assertThat(repository.findLatest(DATE, Market.A_SHARE))
                .hasValueSatisfying(v -> assertThat(v.items().get(0).subjectId()).isEqualTo(91L));
        assertThat(repository.findLatest(DATE, Market.HK))
                .hasValueSatisfying(v -> assertThat(v.batch().version()).isEqualTo(2));
        assertThat(repository.find(DATE, 2, Market.A_SHARE)).isEmpty(); // A 股无 v2

        // 版本列表 market 过滤回显
        assertThat(repository.listVersions(DATE, Market.HK, 200))
                .extracting(VersionSummary::market, VersionSummary::version)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Market.HK, 2),
                        org.assertj.core.groups.Tuple.tuple(Market.HK, 1));

        // 昨日榜单市场内（HK 的 prev 不含 A 股行）
        assertThat(repository.findPreviousTop("2100-01-01", Market.HK))
                .extracting(PrevSubject::subjectId)
                .containsExactly(92L);

        // hits-v1 原料市场过滤
        assertThat(repository.listTopByMaxVersion(Market.US))
                .extracting(MarketTopRepository.RankedSubject::subjectId)
                .containsExactly(91L);
    }

    private static MarketTopBatchRow marketBatch(Market market, String date, int version) {
        MarketTopBatchRow daily = batch(date, version, false);
        return new MarketTopBatchRow(
                market,
                daily.rankDate(),
                daily.version(),
                daily.triggerSource(),
                daily.snapshotDate(),
                daily.funnelStatsJson(),
                daily.degraded(),
                daily.degradedReason(),
                daily.droppedSubjectsJson(),
                daily.diveCostMicros(),
                daily.diveLlmCalls(),
                daily.promptVersion(),
                daily.basis(),
                daily.triggerEventsJson(),
                daily.createdAt());
    }

    private static MarketTopRankRow marketRank(
            Market market, String date, int version, int rankNo, long subjectId) {
        return new MarketTopRankRow(
                market,
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
                "FACTOR_ONLY",
                null,
                "摘要",
                "{}",
                3,
                "2099-12-29",
                null,
                "NEW",
                "mt-v1:hkus:...",
                "2099-12-31T10:00:00Z");
    }

    private static MarketTopBatchRow eventBatch(
            String date, int version, String triggerEventsJson) {
        MarketTopBatchRow daily = batch(date, version, false);
        return new MarketTopBatchRow(
                daily.market(),
                daily.rankDate(),
                daily.version(),
                "EVENT",
                daily.snapshotDate(),
                daily.funnelStatsJson(),
                daily.degraded(),
                daily.degradedReason(),
                daily.droppedSubjectsJson(),
                daily.diveCostMicros(),
                daily.diveLlmCalls(),
                daily.promptVersion(),
                daily.basis(),
                triggerEventsJson,
                daily.createdAt());
    }
}
