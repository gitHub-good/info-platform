package com.info.platform.application.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.policy.PolicyScopeQueryService.PolicyScopePage;
import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FeedItemRepository.LibraryFilter;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * PolicyScopeQueryService 集成测试（V2.3-M23 T201，Gate 2 前半断言，方案 §7）：policy-scope-v1 口径 —— ① 政策源 PASS ∪
 * ② 全源 L1=监管·政策（DONE），仅 PASS、软删源排除、days 窗；<b>口径对账</b>（政策页 total == 独立 SQL 复算 且结果 ⊆ 资讯库同条件 PASS
 * 筛选）；sourceCode 显式选源旁路 scope（宏观源可显式选出数）；industry L1 口径（申万 main/sub、容器仅 main）；keyword LIKE
 * 转义；subjectCode ∪ 关联集行业并集（详情分区 ①② 前置）；keyset 游标续取。
 *
 * <p>断言口径：运行库含 30+ 现役源真实条目（政策页窗内约百条级），故 <b>种子内精确、种子外从宽</b>——期望集/排除集基于本测试 种子条目（确定），总量对账走独立 SQL
 * 复算（全库同谓词，精确相等）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PolicyScopeQueryServiceIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 种子专属回联标的 code（真实标的池不存在——subjectCode 单路断言可精确）。 */
    private static final String SEED_SUBJECT = "SH999201";

    @Autowired private PolicyScopeQueryService service;
    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private FeedItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> seededNewsIds = new java.util.ArrayList<>();

    private long policySourceId; // t201scope-pol（政策）
    private long macroSourceId; // t201scope-mac（宏观）
    private long mediaSourceId; // t201scope-med（媒体）
    private long deletedSourceId; // t201scope-del（政策·软删）

    private long p1; // 政策源 PASS DONE 监管·政策（matched SEED_SUBJECT）——①
    private long p2; // 政策源 PASS L1 PENDING——①（政策源 PASS 不要求 L1）
    private long p3; // 政策源 NOISE——排除（非 PASS）
    private long p4; // 宏观源 PASS DONE 监管·政策——②（任意源）
    private long p5; // 宏观源 PASS DONE 宏观——默认口径外，sourceCode 旁路可选
    private long p6; // 媒体源 PASS DONE 电子/半导体——scope 外（industry 命中也不泄入）
    private long p7; // 政策源 PASS DONE 公用事业——① + 行业命中
    private long p8; // 媒体源 PASS DONE 监管·政策——②
    private long p9; // 软删政策源 PASS——排除（软删源）
    private long p10; // 政策源 PASS DONE 监管·政策（10 天前）——days=7 窗外、days=30 窗内
    private long p11; // 政策源 PASS DONE 银行 matched SEED_SUBJECT——subjectCode 回联命中

    @BeforeEach
    void setUp() {
        policySourceId = seedSource("t201scope-pol", "政策");
        macroSourceId = seedSource("t201scope-mac", "宏观");
        mediaSourceId = seedSource("t201scope-med", "媒体");
        deletedSourceId = seedSource("t201scope-del", "政策");
        jdbcTemplate.update("UPDATE info_source SET deleted = 1 WHERE id = ?", deletedSourceId);

        Instant now = Instant.now();
        Instant recent = now.minusSeconds(3600);
        Instant older = recent.minusSeconds(600);
        Instant tenDaysAgo = now.minusSeconds(10 * 24 * 3600L);
        p1 = insertItem(policySourceId, "央行宣布降准50%个基点", "货币政策宽松", recent);
        p2 = insertItem(policySourceId, "政策源未分类条目", null, older);
        p3 = insertItem(policySourceId, "广告：点击领取优惠券", null, older);
        p4 = insertItem(macroSourceId, "宏观源监改条目", null, older);
        p5 = insertItem(macroSourceId, "统计局发布 CPI 数据", "宏观物价", older);
        p6 = insertItem(mediaSourceId, "媒体半导体行业报道", "芯片产业链", older);
        p7 = insertItem(policySourceId, "电力价格机制政策", "公用事业", older);
        p8 = insertItem(mediaSourceId, "媒体监改报道", null, older);
        p9 = insertItem(deletedSourceId, "软删源政策条目", null, older);
        p10 = insertItem(policySourceId, "十天前的监管政策", null, tenDaysAgo);
        p11 = insertItem(policySourceId, "贵州茅台相关银行政策", null, older);

        String matched =
                "[{\"code\":\"" + SEED_SUBJECT + "\",\"name\":\"测试标的\",\"industry\":\"公用事业\"}]";
        insertAnalysis(p1, "PASS", "DONE", "监管·政策", null, matched);
        insertAnalysis(p2, "PASS", "PENDING", null, null, null);
        insertAnalysis(p3, "NOISE", "PENDING", null, null, null);
        insertAnalysis(p4, "PASS", "DONE", "监管·政策", null, null);
        insertAnalysis(p5, "PASS", "DONE", "宏观", null, null);
        insertAnalysis(p6, "PASS", "DONE", "电子", "半导体", null);
        insertAnalysis(p7, "PASS", "DONE", "公用事业", null, null);
        insertAnalysis(p8, "PASS", "DONE", "监管·政策", null, null);
        insertAnalysis(p9, "PASS", "DONE", "监管·政策", null, null);
        insertAnalysis(p10, "PASS", "DONE", "监管·政策", null, null);
        insertAnalysis(p11, "PASS", "DONE", "银行", null, matched);
    }

    @AfterEach
    void cleanup() {
        if (!seededNewsIds.isEmpty()) {
            String ids = String.join(",", seededNewsIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM news_analysis WHERE news_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM news_item WHERE id IN (" + ids + ")");
            seededNewsIds.clear();
        }
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN"
                        + " (SELECT id FROM info_source WHERE source_code LIKE 't201scope%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't201scope%'");
    }

    @Test
    void scope_defaultWindow_policySourcePassOrL1Container_independentSqlReconciles() {
        // Act：默认口径（days=7 窗；limit=200 护栏内种子必达）
        PolicyScopePage page = service.list(PolicyScopeFilter.ofWindow(7, 200, 0));
        List<Long> ids = page.rows().stream().map(PolicyScopeRow::newsId).toList();

        // Assert ①：种子命中集 = ①政策源 PASS（PENDING 也入）∪ ②全源 L1=监管·政策
        assertThat(ids).contains(p1, p2, p4, p7, p8, p11);
        // 排除：NOISE（p3）/ 宏观 L1（p5）/ scope 外行业（p6）/ 软删源（p9）/ 窗外（p10）
        assertThat(ids).doesNotContain(p3, p5, p6, p9, p10);
        // 种子内相对序：p1（1h 前，种子最新）在最前，其余种子按 published DESC id DESC（p11>p8>p7>p4>p2）
        assertThat(ids.indexOf(p1)).isZero();
        assertThat(indexesSorted(ids, List.of(p1, p11, p8, p7, p4, p2))).isTrue();

        // Gate 2 口径对账：total == 独立 SQL 复算（政策源 PASS ∪ L1=监管·政策，同窗同软删谓词）
        assertThat(page.total()).isEqualTo(independentScopeCount(7));

        // Gate 2 口径对账：政策页结果 ⊆ 资讯库同条件（PASS）筛选结果（同窗）
        assertThat(libraryPassIdsWithinWindow(7)).containsAll(ids);

        // 命中行携带源展示列与 L1 归类产物（matchedSubjects 直读依据）
        PolicyScopeRow p1Row =
                page.rows().stream().filter(r -> r.newsId() == p1).findFirst().orElseThrow();
        assertThat(p1Row.sourceCode()).isEqualTo("t201scope-pol");
        assertThat(p1Row.sourceCategory()).isEqualTo("政策");
        assertThat(p1Row.mainCategory()).isEqualTo("监管·政策");
        assertThat(p1Row.matchedSubjectsJson()).contains(SEED_SUBJECT);
    }

    @Test
    void scope_days30_windowExtended_includesOlderItem() {
        PolicyScopePage page = service.list(PolicyScopeFilter.ofWindow(30, 200, 0));

        assertThat(page.rows()).extracting(PolicyScopeRow::newsId).contains(p10);
        assertThat(page.total()).isEqualTo(independentScopeCount(30));
    }

    @Test
    void scope_industryContainer_mainOnlyMatch() {
        // industry=监管·政策：仅 main 命中（容器不走 sub）——p1/p4/p8 入；p2（PENDING 无 main）/p7（公用事业）/p11（银行）不入
        PolicyScopePage page =
                service.list(
                        new PolicyScopeFilter(
                                7, "监管·政策", null, null, null, Set.of(), null, 200, 0));
        List<Long> ids = page.rows().stream().map(PolicyScopeRow::newsId).toList();

        assertThat(ids).contains(p1, p4, p8);
        assertThat(ids).doesNotContain(p2, p7, p11);
    }

    @Test
    void scope_industrySw_mainOrSubMatch_withinScopeOnly() {
        // industry=公用事业：政策源条目 main 命中（p7）；scope 外条目即使行业命中也不泄入（p6 电子非 scope）
        PolicyScopePage page =
                service.list(
                        new PolicyScopeFilter(7, "公用事业", null, null, null, Set.of(), null, 200, 0));
        List<Long> ids = page.rows().stream().map(PolicyScopeRow::newsId).toList();

        assertThat(ids).contains(p7);
        assertThat(ids).doesNotContain(p6);
        // 行业过滤叠加在 scope 之上：scope 内无该行业种子条目 → 无种子命中（非泄入全部）
        PolicyScopePage coal =
                service.list(
                        new PolicyScopeFilter(7, "煤炭", null, null, null, Set.of(), null, 200, 0));
        assertThat(coal.rows().stream().map(PolicyScopeRow::newsId).toList())
                .doesNotContain(p1, p2, p4, p7, p8, p11);
    }

    @Test
    void scope_sourceCodeExplicit_bypassesScope_macroSourceSelectable() {
        // sourceCode 显式选源旁路 ①②（ADR-0062 随批 4）：宏观源可显式选出数——p5（L1=宏观，默认口径外）入选；
        // 本测试源专属 → 精确断言
        PolicyScopePage page =
                service.list(
                        new PolicyScopeFilter(
                                7, null, "t201scope-mac", null, null, Set.of(), null, 50, 0));

        assertThat(page.rows())
                .extracting(PolicyScopeRow::newsId)
                .containsExactlyInAnyOrder(p4, p5);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void scope_keyword_titleOrSummaryHit_likeEscaped() {
        // keyword 命中标题（p1 标题含字面 %）；LIKE 通配符按字面转义（% 不作通配、_ 不作单字通配）
        PolicyScopePage page =
                service.list(
                        new PolicyScopeFilter(
                                7, null, null, "降准50%个基点", null, Set.of(), null, 50, 0));
        assertThat(page.rows()).extracting(PolicyScopeRow::newsId).containsExactly(p1);

        PolicyScopePage underscore =
                service.list(
                        new PolicyScopeFilter(
                                7, null, null, "降准50_个基点", null, Set.of(), null, 50, 0));
        assertThat(underscore.rows()).isEmpty();
    }

    @Test
    void scope_subjectUnion_directBacklinkOrIndustrySet() {
        // ① 直接回联（p1/p11 matched SEED_SUBJECT）∪ ② 行业∈关联集（p7 公用事业）并集——详情分区过滤前置
        PolicyScopePage union =
                service.list(
                        new PolicyScopeFilter(
                                7, null, null, null, SEED_SUBJECT, Set.of("公用事业"), null, 200, 0));
        List<Long> unionIds = union.rows().stream().map(PolicyScopeRow::newsId).toList();
        assertThat(unionIds).contains(p1, p7, p11);
        assertThat(unionIds).doesNotContain(p8); // 监管·政策容器条目不因容器命中行业集

        // 单路退化：仅 subjectCode → 种子池唯一回联源，精确断言（p1 最新在前）
        PolicyScopePage subjectOnly =
                service.list(
                        new PolicyScopeFilter(
                                7, null, null, null, SEED_SUBJECT, Set.of(), null, 200, 0));
        assertThat(subjectOnly.rows()).extracting(PolicyScopeRow::newsId).containsExactly(p1, p11);
    }

    @Test
    void scope_keysetCursor_pageThroughWithoutOverlap() {
        // keyset 游标：首页 3 条 → beforeCursor=末条 → 次页取排序位置严格更早的行（无重叠、不漏）
        PolicyScopePage first = service.list(PolicyScopeFilter.ofWindow(7, 3, 0));
        assertThat(first.rows()).hasSize(3);
        long cursor = first.rows().get(2).newsId();

        PolicyScopePage second =
                service.list(
                        new PolicyScopeFilter(7, null, null, null, null, Set.of(), cursor, 200, 0));

        List<Long> firstIds = first.rows().stream().map(PolicyScopeRow::newsId).toList();
        List<Long> secondIds = second.rows().stream().map(PolicyScopeRow::newsId).toList();
        assertThat(secondIds).noneMatch(firstIds::contains);
        assertThat(firstIds.size() + secondIds.size()).isEqualTo(first.total());
    }

    @Test
    void findByNewsId_inScopePresent_outOfScopeOrMissingEmpty() {
        assertThat(service.findByNewsId(p1)).isPresent();
        // scope 外条目（p5 宏观/p6 媒体电子）→ empty（政策详情 404 语义：政策页宇宙=政策类条目）
        assertThat(service.findByNewsId(p5)).isEmpty();
        assertThat(service.findByNewsId(p6)).isEmpty();
        assertThat(service.findByNewsId(999_999_999L)).isEmpty();
    }

    @Test
    void daysClamp_m9Precedent() {
        assertThat(PolicyScopeQueryService.clampDays(0)).isEqualTo(7);
        assertThat(PolicyScopeQueryService.clampDays(-3)).isEqualTo(7);
        assertThat(PolicyScopeQueryService.clampDays(30)).isEqualTo(30);
        assertThat(PolicyScopeQueryService.clampDays(365)).isEqualTo(90);
    }

    // ---- 种子与对账辅助 ----

    /** 种子 id 子序列的相对序断言（published DESC, id DESC 在种子内保持）。 */
    private static boolean indexesSorted(List<Long> ids, List<Long> expectedOrder) {
        int last = -1;
        for (long expected : expectedOrder) {
            int index = ids.indexOf(expected);
            if (index < 0 || index <= last) {
                return false;
            }
            last = index;
        }
        return true;
    }

    private long seedSource(String code, String category) {
        InfoSource source =
                InfoSource.create(
                        code,
                        code + "源",
                        category,
                        AdapterType.RSS,
                        null,
                        "https://example.com/" + code,
                        null,
                        60,
                        true,
                        false);
        infoSourceRepository.save(source);
        return source.getId();
    }

    private long insertItem(long sourceId, String title, String summary, Instant publishedAt) {
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                sourceId,
                                "ext-" + title,
                                title,
                                summary,
                                "https://example.com/n/" + title,
                                null,
                                publishedAt,
                                publishedAt,
                                FeedFingerprint.fingerprint(title + sourceId, publishedAt))));
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE source_id = ? AND title = ?",
                        Long.class,
                        sourceId,
                        title);
        assertThat(id).as("种子条目应已落库: %s", title).isNotNull();
        seededNewsIds.add(id);
        return id;
    }

    private void insertAnalysis(
            long newsId,
            String l0Result,
            String l1Status,
            String mainCategory,
            String subIndustry,
            String matchedSubjectsJson) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO news_analysis"
                        + " (news_id, l0_result, l1_status, main_category, sub_industry,"
                        + " matched_subjects, l1_attempts, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                newsId,
                l0Result,
                l1Status,
                mainCategory,
                subIndustry,
                matchedSubjectsJson,
                now,
                now);
    }

    /** Gate 2 独立复算 SQL（与读口 SQL 谓词逐条对应——对账断言的「第二只眼」）。 */
    private long independentScopeCount(int days) {
        String windowStart =
                LocalDate.now(SHANGHAI)
                        .minusDays(days)
                        .atStartOfDay(SHANGHAI)
                        .toInstant()
                        .toString();
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM news_item ni
                          JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                          JOIN news_analysis na ON na.news_id = ni.id
                         WHERE ni.status = 1
                           AND na.l0_result = 'PASS'
                           AND ni.published_at >= ?
                           AND (s.category = '政策'
                                OR (na.l1_status = 'DONE' AND na.main_category = '监管·政策'))
                        """,
                        Long.class,
                        windowStart);
        return count == null ? 0 : count;
    }

    /** 资讯库同条件（PASS）窗内条目 id 集——政策页结果必须是其子集。 */
    private List<Long> libraryPassIdsWithinWindow(int days) {
        String windowStart =
                LocalDate.now(SHANGHAI)
                        .minusDays(days)
                        .atStartOfDay(SHANGHAI)
                        .toInstant()
                        .toString();
        List<Long> ids = new java.util.ArrayList<>();
        for (int page = 1; page <= 50; page++) {
            List<FeedItemRepository.LibraryRow> rows =
                    itemRepository.findPage(
                            new LibraryFilter(null, null, L0Result.PASS, null, null, null),
                            page,
                            100);
            rows.stream()
                    .filter(row -> row.item().publishedAt().toString().compareTo(windowStart) >= 0)
                    .map(row -> row.item().id())
                    .forEach(ids::add);
            if (rows.size() < 100) {
                break;
            }
        }
        return ids;
    }
}
