package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.analysis.L1Status;
import com.info.platform.domain.analysis.L2Status;
import com.info.platform.domain.analysis.NewsAnalysis;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.AiExclusion;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceConfig;
import java.time.Instant;
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
 * NewsAnalysisRepository 集成测试（T120，ADR-0046 裁决 1）：建行幂等（UNIQUE(news_id) 重入收敛）、findUnanalyzed
 * 缓冲窗与软删源排除、 近重复 PASS 池窗口、L1 待处理过滤（NOISE/NEAR_DUP 不进 L1——方案 §6 红线断言面）、applyL1Result 条件 UPDATE 幂等（已
 * DONE 不重复归类）、markL1Failed 计数推进、当日三态计数。V23 四表由 Flyway 内存库建出（表存在性一并断言）。 t120_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class NewsAnalysisRepositoryImplTest {

    @Autowired private NewsAnalysisRepository repository;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceA;

    private Long sourceB;

    @BeforeEach
    void setUpSources() {
        sourceA = newSource("t120_a", false);
        sourceB = newSource("t120_b", true);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't12%'))");
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't12%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't12%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't120_%'");
    }

    private Long newSource(String code, boolean deleted) {
        InfoSource source =
                InfoSource.create(
                        code,
                        code,
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + code,
                        null,
                        15,
                        true,
                        false);
        if (deleted) {
            source.markDeleted();
        }
        infoSourceRepository.save(source);
        return source.getId();
    }

    /** 直插 news_item（精确控制 created_at/published_at——缓冲窗与池窗口用例的控制变量）。 */
    private long newNews(long sourceId, String title, String createdAtIso, String publishedAtIso) {
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                sourceId,
                "t120_" + System.nanoTime(),
                title,
                "摘要-" + title,
                "https://example.com/n/" + title.hashCode(),
                publishedAtIso,
                publishedAtIso,
                "fp-" + title.hashCode() + "-" + System.nanoTime(),
                createdAtIso,
                createdAtIso);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM news_item WHERE title = ?", Long.class, title);
    }

    @Test
    void migration_v23_fourTablesExist() {
        for (String table :
                List.of(
                        "news_analysis",
                        "event_item",
                        "industry_heat_snapshot",
                        "industry_daily_report")) {
            Integer count =
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).isZero();
        }
    }

    @Test
    void insertIgnoreBatch_uniqueNewsId_reentryConverges() {
        long newsId =
                newNews(sourceA, "央行宣布下调存款准备金率", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");

        int first =
                repository.insertIgnoreBatch(
                        List.of(NewsAnalysis.newForL0(newsId, L0Result.PASS, null, null)));
        int second =
                repository.insertIgnoreBatch(
                        List.of(NewsAnalysis.newForL0(newsId, L0Result.NEAR_DUP, newsId, "重入行")));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero(); // UNIQUE(news_id) 吸收重入
    }

    @Test
    void findUnanalyzed_respectsBufferWindowAndSoftDeletedSource() {
        String cutoff = "2026-09-22T03:30:00Z";
        long buffered =
                newNews(sourceA, "缓冲期外的旧条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long fresh = newNews(sourceA, "缓冲期内的新条目", "2026-09-22T03:35:00Z", "2026-09-22T02:00:00Z");
        long softDeleted =
                newNews(sourceB, "软删源条目不应出现", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");

        List<Long> ids =
                repository.findUnanalyzed(cutoff, List.of(), 100).stream()
                        .map(NewsAnalysisRepository.NewsCandidate::newsId)
                        .toList();

        assertThat(ids).contains(buffered).doesNotContain(fresh, softDeleted);
    }

    @Test
    void findUnanalyzed_skipsAlreadyAnalyzed() {
        long analyzed =
                newNews(sourceA, "已有管道行的条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long unanalyzed =
                newNews(sourceA, "尚无管道行的条目二", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(analyzed, L0Result.NOISE, null, "noise:keyword:广告")));

        List<Long> ids =
                repository.findUnanalyzed(Instant.now().toString(), List.of(), 100).stream()
                        .map(NewsAnalysisRepository.NewsCandidate::newsId)
                        .toList();

        assertThat(ids).contains(unanalyzed).doesNotContain(analyzed);
    }

    @Test
    void findPassPoolSince_onlyPassRowsInWindow() {
        long inWindow = newNews(sourceA, "窗口内正常条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long noise = newNews(sourceA, "窗口内噪音条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long stale = newNews(sourceA, "窗口外老条目", "2026-09-20T03:00:00Z", "2026-09-20T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(inWindow, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(noise, L0Result.NOISE, null, "noise:keyword:广告"),
                        NewsAnalysis.newForL0(stale, L0Result.PASS, null, null)));

        List<Long> poolIds =
                repository.findPassPoolSince("2026-09-21T00:00:00Z", 100).stream()
                        .map(NewsAnalysisRepository.NewsCandidate::newsId)
                        .toList();

        assertThat(poolIds).containsExactly(inWindow);
    }

    @Test
    void findPendingForL1_filtersL0StatusAttemptsAndWindow() {
        long pending = newNews(sourceA, "待归类正常条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long noise = newNews(sourceA, "噪音条目不进L1", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long nearDupMain =
                newNews(sourceA, "近重复主条目本身可归类", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long nearDup =
                newNews(sourceA, "近重复从条目不进L1", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long exhausted =
                newNews(sourceA, "重试耗尽条目不再进批", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long done = newNews(sourceA, "已完成条目不重复归类", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(pending, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(noise, L0Result.NOISE, null, "noise:keyword:广告"),
                        NewsAnalysis.newForL0(nearDupMain, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(
                                nearDup, L0Result.NEAR_DUP, nearDupMain, "hamming=1;lev=0.02"),
                        NewsAnalysis.newForL0(exhausted, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(done, L0Result.PASS, null, null)));
        repository.markL1Failed(List.of(exhausted, exhausted, exhausted));
        repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        done, "银行", null, null, 0.9, false, null, "v1.0", Instant.now()));

        List<Long> ids =
                repository.findPendingForL1("2026-09-21T00:00:00Z", 3, List.of(), 100).stream()
                        .map(NewsAnalysisRepository.ClassificationCandidate::newsId)
                        .toList();

        assertThat(ids).containsExactly(pending, nearDupMain); // NOISE/NEAR_DUP/耗尽/DONE 全部排除
    }

    @Test
    void findPendingForL1_windowExcludesStaleRows() {
        long stale =
                newNews(sourceA, "超窗旧条目留待次日窗口外", "2026-09-20T03:00:00Z", "2026-09-20T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(stale, L0Result.PASS, null, null)));

        // analysis 行 created_at = 建行时刻（L0 时间）：窗口起点推到未来即排除现存行
        String futureWindowStart = Instant.now().plusSeconds(3600).toString();
        List<Long> ids =
                repository.findPendingForL1(futureWindowStart, 3, List.of(), 100).stream()
                        .map(NewsAnalysisRepository.ClassificationCandidate::newsId)
                        .toList();

        assertThat(ids).doesNotContain(stale);
    }

    @Test
    void findPendingForL1_carriesRenderFields() {
        long newsId = newNews(sourceA, "渲染字段完整性条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(newsId, L0Result.PASS, null, null)));

        NewsAnalysisRepository.ClassificationCandidate candidate =
                repository.findPendingForL1("2026-09-21T00:00:00Z", 3, List.of(), 100).get(0);

        assertThat(candidate.newsId()).isEqualTo(newsId);
        assertThat(candidate.title()).isEqualTo("渲染字段完整性条目");
        assertThat(candidate.summary()).isEqualTo("摘要-渲染字段完整性条目");
        assertThat(candidate.sourceName()).isEqualTo("t120_a");
        assertThat(candidate.publishedAt()).isEqualTo(Instant.parse("2026-09-22T02:00:00Z"));
        assertThat(candidate.fetchedAt()).isEqualTo(Instant.parse("2026-09-22T02:00:00Z"));
    }

    @Test
    void applyL1Result_conditionalUpdate_idempotentOnDone() {
        long newsId =
                newNews(sourceA, "条件落库幂等验证条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(newsId, L0Result.PASS, null, null)));

        int first =
                repository.applyL1Result(
                        new NewsAnalysisRepository.L1Write(
                                newsId,
                                "食品饮料",
                                null,
                                null,
                                0.92,
                                false,
                                "[]",
                                "v1.0",
                                Instant.parse("2026-09-22T03:01:00Z")));
        int second =
                repository.applyL1Result(
                        new NewsAnalysisRepository.L1Write(
                                newsId,
                                "市场·其他",
                                "银行",
                                null,
                                0.10,
                                true,
                                "[]",
                                "v1.0",
                                Instant.parse("2026-09-22T03:02:00Z")));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero(); // 已 DONE 行条件 UPDATE 不命中（幂等红线）
        Map<String, Object> row =
                jdbcTemplate.queryForMap("SELECT * FROM news_analysis WHERE news_id = ?", newsId);
        assertThat(row.get("main_category")).isEqualTo("食品饮料");
        assertThat(row.get("l1_status")).isEqualTo("DONE");
        assertThat((double) row.get("confidence")).isEqualTo(0.92);
        assertThat(row.get("l1_prompt_version")).isEqualTo("v1.0");
        assertThat(row.get("classified_at")).isEqualTo("2026-09-22T03:01:00Z");
    }

    @Test
    void markL1Failed_incrementsAttemptsAndSetsFailed() {
        long newsId =
                newNews(sourceA, "失败计数推进验证条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(newsId, L0Result.PASS, null, null)));

        repository.markL1Failed(List.of(newsId));
        repository.markL1Failed(List.of(newsId));

        Map<String, Object> row =
                jdbcTemplate.queryForMap("SELECT * FROM news_analysis WHERE news_id = ?", newsId);
        assertThat(row.get("l1_status")).isEqualTo(L1Status.FAILED.name());
        assertThat(row.get("l1_attempts")).isEqualTo(2);
        assertThat(repository.markL1Failed(List.of())).isZero();
    }

    @Test
    void counts_byL0AndL1_sinceWindow() {
        long pass = newNews(sourceA, "计数-正常条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long noise = newNews(sourceA, "计数-广告条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(pass, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(noise, L0Result.NOISE, null, "noise:keyword:广告")));
        repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        pass, "银行", null, null, 0.9, false, null, "v1.0", Instant.now()));

        Map<String, Long> l0 = repository.countL0ByResultSince("2026-09-22T00:00:00Z");
        Map<String, Long> l1 = repository.countL1ByStatusSince("2026-09-22T00:00:00Z");

        assertThat(l0)
                .containsEntry("PASS", 1L)
                .containsEntry("NOISE", 1L)
                .doesNotContainKey("NEAR_DUP");
        assertThat(l1).containsEntry("DONE", 1L).containsEntry("PENDING", 1L);
        // 窗口起点推到未来（现存行 created_at 均在其前）→ 空计数
        assertThat(repository.countL0ByResultSince(Instant.now().plusSeconds(3600).toString()))
                .isEmpty();
    }

    // ---- L2（T122，方案 §4.4）----

    /** 建 PASS 行并完成 L1（L2 候选前置：PASS + DONE）。 */
    private long classifiedNews(long sourceId, String title) {
        long newsId = newNews(sourceId, title, "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(newsId, L0Result.PASS, null, null)));
        repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        newsId, "银行", null, null, 0.9, false, null, "v1.0", Instant.now()));
        return newsId;
    }

    @Test
    void findL2Candidates_filtersStatusWindowAttemptsAndExclusion() {
        long todayHit = classifiedNews(sourceA, "L2候选-当日命中条目");
        long pendingL1 =
                newNews(sourceA, "L2候选-未归类条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(pendingL1, L0Result.PASS, null, null)));
        long extracted = classifiedNews(sourceA, "L2候选-已提取终态条目");
        repository.markL2Selected(List.of(extracted));
        repository.applyL2Result(new NewsAnalysisRepository.L2Write(extracted, L2Status.EXTRACTED));
        long exhausted = classifiedNews(sourceA, "L2候选-重试耗尽条目");
        repository.markL2Failed(List.of(exhausted, exhausted, exhausted));
        long excluded = classifiedNews(sourceB, "L2候选-排除源条目");
        long oldDebt = classifiedNews(sourceA, "L2候选-旧账条目");
        jdbcTemplate.update(
                "UPDATE news_analysis SET l2_status='DEFERRED', created_at='2026-09-21T03:00:00Z'"
                        + " WHERE news_id=?",
                oldDebt);

        // 当日窗口 = 过去（全部行 created_at 在其后）；旧账窗口 = 2026-09-21 起（oldDebt 行 created_at 已回拨）
        List<Long> ids =
                repository
                        .findL2Candidates(
                                "2026-09-21T00:00:00Z",
                                "2026-09-21T00:00:00Z",
                                3,
                                List.of(sourceB),
                                100)
                        .stream()
                        .map(NewsAnalysisRepository.L2Candidate::newsId)
                        .toList();

        // PENDING/EXTRACTED/耗尽 FAILED/排除源 全部排除；SKIP 命中 + DEFERRED 旧账入选
        assertThat(ids).containsExactlyInAnyOrder(todayHit, oldDebt);

        NewsAnalysisRepository.L2Candidate candidate =
                repository
                        .findL2Candidates(
                                "2026-09-21T00:00:00Z", "2026-09-21T00:00:00Z", 3, List.of(), 100)
                        .get(0);
        assertThat(candidate.sourceCategory()).isEqualTo("快讯");
        assertThat(candidate.mainCategory()).isEqualTo("银行");
        assertThat(candidate.currentL2()).isIn(L2Status.SKIP, L2Status.DEFERRED);
    }

    @Test
    void l2Writes_scoreSelectionDeferredResultAndFailed() {
        long newsId = classifiedNews(sourceA, "L2写路径-全字段条目");

        int scored = repository.updateImportanceScores(Map.of(newsId, 3.5));
        int selected = repository.markL2Selected(List.of(newsId));
        int applied =
                repository.applyL2Result(
                        new NewsAnalysisRepository.L2Write(newsId, L2Status.EXTRACTED));

        assertThat(scored).isEqualTo(1);
        assertThat(selected).isEqualTo(1);
        assertThat(applied).isEqualTo(1);
        Map<String, Object> row =
                jdbcTemplate.queryForMap("SELECT * FROM news_analysis WHERE news_id = ?", newsId);
        assertThat((Double) row.get("importance_score")).isEqualTo(3.5);
        assertThat(row.get("l2_status")).isEqualTo(L2Status.EXTRACTED.name());
        // 终态再推进不命中（幂等：EXTRACTED 后不再改写）
        assertThat(
                        repository.applyL2Result(
                                new NewsAnalysisRepository.L2Write(newsId, L2Status.NO_EVENT)))
                .isZero();

        long other = classifiedNews(sourceA, "L2写路径-延迟与失败条目");
        repository.markL2Deferred(List.of(other));
        repository.markL2Failed(List.of(other));
        Map<String, Object> deferredRow =
                jdbcTemplate.queryForMap("SELECT * FROM news_analysis WHERE news_id = ?", other);
        assertThat(deferredRow.get("l2_status")).isEqualTo(L2Status.FAILED.name());
        assertThat(((Number) deferredRow.get("l2_attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void l2Counts_doneBaseProcessedAndStatusCounts() {
        long base = classifiedNews(sourceA, "L2计数-配额基数条目");
        long processed = classifiedNews(sourceA, "L2计数-已处理条目");
        repository.markL2Selected(List.of(processed));
        repository.applyL2Result(new NewsAnalysisRepository.L2Write(processed, L2Status.EXTRACTED));
        long deferred = classifiedNews(sourceA, "L2计数-延迟条目");
        repository.markL2Deferred(List.of(deferred));
        long pendingOnly =
                newNews(sourceA, "L2计数-未归类不计基数", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(NewsAnalysis.newForL0(pendingOnly, L0Result.PASS, null, null)));

        String since = "2026-09-22T00:00:00Z";
        assertThat(repository.countL1DoneSince(since)).isEqualTo(3);
        // 已处理 = 终态（EXTRACTED/NO_EVENT/FAILED）且 updated_at ≥ since；DEFERRED 不算消耗
        assertThat(repository.countL2ProcessedSince(since)).isEqualTo(1);
        Map<String, Long> l2 = repository.countL2ByStatusSince(since);
        assertThat(l2)
                .containsEntry("SKIP", 2L) // base + 未归类条目（缺省 SKIP，不进配额基数）
                .containsEntry("EXTRACTED", 1L)
                .containsEntry("DEFERRED", 1L);
    }

    // ---- T125：护栏/排除面（SLA 口径 / 净入库计数 / aiExclusion 排除下传） ----

    @Test
    void countL1SlaSince_doneDenominatorAnd30MinWindow() {
        // fetched 02:00：29min 内完成（02:29）计分子；31min（02:31）不计；PENDING 不进分母
        long fast = newNews(sourceA, "SLA 快条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long slow = newNews(sourceA, "SLA 慢条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long pending =
                newNews(sourceA, "SLA 未完成条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(fast, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(slow, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(pending, L0Result.PASS, null, null)));
        repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        fast,
                        "银行",
                        null,
                        null,
                        0.9,
                        false,
                        null,
                        "v1.0",
                        Instant.parse("2026-09-22T02:29:00Z")));
        repository.applyL1Result(
                new NewsAnalysisRepository.L1Write(
                        slow,
                        "银行",
                        null,
                        null,
                        0.9,
                        false,
                        null,
                        "v1.0",
                        Instant.parse("2026-09-22T02:31:00Z")));

        NewsAnalysisRepository.L1SlaStats stats =
                repository.countL1SlaSince("2026-09-21T00:00:00Z");

        assertThat(stats.done()).isEqualTo(2);
        assertThat(stats.within30Min()).isEqualTo(1);
    }

    @Test
    void countNewsItemsCreatedSince_countsIntakeWindow() {
        long today = newNews(sourceA, "净入库当日条目", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        newNews(sourceA, "净入库昨日条目", "2026-09-20T03:00:00Z", "2026-09-20T02:00:00Z");

        assertThat(repository.countNewsItemsCreatedSince("2026-09-21T16:00:00Z"))
                .isEqualTo(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM news_item WHERE id = ?", Long.class, today));
    }

    @Test
    void findUnanalyzed_excludesAllLevelSources() {
        // aiExclusion=ALL 源条目不建 analysis 行（REQ 拍板五-1：L0 排除面）——启用源（非软删）才能隔离排除语义
        long allSource = newConfiguredSource("t125_all", AiExclusion.ALL);
        long excluded =
                newNews(allSource, "ALL 排除源条目不建行", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        long kept = newNews(sourceA, "正常源条目照常建行", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");

        List<Long> ids =
                repository
                        .findUnanalyzed(Instant.now().toString(), List.of(allSource), 100)
                        .stream()
                        .map(NewsAnalysisRepository.NewsCandidate::newsId)
                        .toList();

        assertThat(ids).contains(kept).doesNotContain(excluded);
    }

    /** 建带 AI 排除档位配置的启用源（t125_ 前缀走既有清理链）。 */
    private long newConfiguredSource(String code, AiExclusion level) {
        InfoSource source =
                InfoSource.create(
                        code,
                        code,
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + code,
                        new SourceConfig(
                                null, null, null, null, null, null, null, null, null, null, level),
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        return source.getId();
    }

    @Test
    void findPendingForL1_excludesAllLevelSources() {
        long allSource = newConfiguredSource("t125_all_l1", AiExclusion.ALL);
        long excluded =
                newNews(
                        allSource,
                        "ALL 排除源条目不进 L1",
                        "2026-09-22T03:00:00Z",
                        "2026-09-22T02:00:00Z");
        long kept = newNews(sourceA, "正常源条目进 L1", "2026-09-22T03:00:00Z", "2026-09-22T02:00:00Z");
        repository.insertIgnoreBatch(
                List.of(
                        NewsAnalysis.newForL0(excluded, L0Result.PASS, null, null),
                        NewsAnalysis.newForL0(kept, L0Result.PASS, null, null)));

        List<Long> ids =
                repository
                        .findPendingForL1("2026-09-21T00:00:00Z", 3, List.of(allSource), 100)
                        .stream()
                        .map(NewsAnalysisRepository.ClassificationCandidate::newsId)
                        .toList();

        assertThat(ids).containsExactly(kept);
    }
}
