package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.FeedItemRepository.LibraryFilter;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * NewsItemsQueryService 资讯库读模型集成测试（T160，M19 V2.1，REQ-20260926-16 拍板一）： news_item LEFT JOIN
 * news_analysis 的过滤/兜底/主条解析真实仓储行为——l0 三态与缺省 PASS 兜底（无 analysis 行）、 l1 主分类过滤（PENDING/无行不含）、q 关键词
 * title/summary 命中与 LIKE 转义、近重复主条 url 解析与主条清理降级、 组合过滤 AND 语义与游标路径回归。
 */
@SpringBootTest
@ActiveProfiles("test")
class NewsItemsLibraryIntegrationTest {

    @Autowired private NewsItemsQueryService service;
    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private FeedItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> seededNewsIds = new java.util.ArrayList<>();

    private Long sourceId;
    private long n1; // PASS / L1 DONE 银行
    private long n2; // NOISE（l0_detail=推广）
    private long n3; // NEAR_DUP（主条 n1）
    private long n4; // PASS / L1 PENDING（未分类）
    private long n5; // 无 analysis 行（保留期清理错位滞留）
    private long n6; // PASS / L1 DONE 电子 低置信

    @BeforeEach
    void setUp() {
        InfoSource source =
                InfoSource.create(
                        "t160lib",
                        "资讯库源",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t160lib",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
        Instant t = Instant.parse("2026-09-22T05:00:00Z");
        n1 = insertItem("央行宣布降息50个基点", "货币政策宽松信号", "https://example.com/n1", t);
        n2 = insertItem("广告：点击领取优惠券", "限时推广50%特惠", "https://example.com/n2", t);
        n3 = insertItem("央行宣布降息50个基点（转载）", null, "https://example.com/n3", t);
        n4 = insertItem("贵州茅台发布年报", "食品饮料龙头业绩", "https://example.com/n4", t);
        n5 = insertItem("滞留条目无分析行", null, "https://example.com/n5", t);
        n6 = insertItem("美股 iPhone 供应链_A_B 动态", null, "https://example.com/n6", t);
        insertAnalysis(n1, "PASS", null, null, "DONE", "银行", 0.92, 0);
        insertAnalysis(n2, "NOISE", null, "推广", "PENDING", null, null, 0);
        insertAnalysis(n3, "NEAR_DUP", n1, "hamming=3;edit=0.21", "PENDING", null, null, 0);
        insertAnalysis(n4, "PASS", null, null, "PENDING", null, null, 0);
        // n5：无 analysis 行（兜底语义样本）
        insertAnalysis(n6, "PASS", null, null, "DONE", "电子", 0.30, 1);
    }

    @AfterEach
    void cleanup() {
        // 显式按种子 id 清 analysis 行：近重复用例会删主条 news_item 行，按 news_item 反查会漏孤儿 analysis 行
        // （泄漏将污染 NewsAnalysisRepositoryImplTest 的全局计数断言——migration_v23 空表前提）
        if (!seededNewsIds.isEmpty()) {
            String ids = String.join(",", seededNewsIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM news_analysis WHERE news_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM news_item WHERE id IN (" + ids + ")");
            seededNewsIds.clear();
        }
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN"
                        + " (SELECT id FROM info_source WHERE source_code LIKE 't160lib')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN"
                        + " (SELECT id FROM info_source WHERE source_code LIKE 't160lib')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't160lib'");
    }

    private long insertItem(String title, String summary, String url, Instant t) {
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                sourceId,
                                "ext-" + title,
                                title,
                                summary,
                                url,
                                null,
                                t,
                                t,
                                FeedFingerprint.fingerprint(title, t))));
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
            Long nearDupOf,
            String l0Detail,
            String l1Status,
            String mainCategory,
            Double confidence,
            int lowConfidence) {
        jdbcTemplate.update(
                "INSERT INTO news_analysis"
                        + " (news_id, l0_result, near_dup_of, l0_detail, l1_status, main_category,"
                        + " confidence, low_confidence, l1_attempts, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                newsId,
                l0Result,
                nearDupOf,
                l0Detail,
                l1Status,
                mainCategory,
                confidence,
                lowConfidence,
                "2026-09-22T05:01:00Z",
                "2026-09-22T05:01:00Z");
    }

    private LibraryFilter filter(String q, L0Result l0, String l1) {
        return new LibraryFilter(sourceId, q, l0, l1, null, null);
    }

    @Test
    void listPaged_l0Pass_includesDonePendingAndFallback_excludesNoiseNearDup() {
        NewsItemsPagedView view = service.listPaged(filter(null, L0Result.PASS, null), 1, 10);

        assertThat(view.items()).extracting(NewsItemView::id).containsExactly(n6, n5, n4, n1);
        assertThat(view.total()).isEqualTo(4);
        // 无 analysis 行兜底：l0=PASS、l1Main=null（未分类灰态）、confidence null、lowConfidence false
        NewsItemView fallback = view.items().get(1);
        assertThat(fallback.id()).isEqualTo(n5);
        assertThat(fallback.l0Result()).isEqualTo("PASS");
        assertThat(fallback.l1Main()).isNull();
        assertThat(fallback.l1Confidence()).isNull();
        assertThat(fallback.lowConfidence()).isFalse();
        assertThat(fallback.nearDupMasterId()).isNull();
        assertThat(fallback.nearDupMasterUrl()).isNull();
        // 低置信 DONE 行：旗标 + 置信度直读
        NewsItemView lowConf = view.items().get(0);
        assertThat(lowConf.id()).isEqualTo(n6);
        assertThat(lowConf.l1Main()).isEqualTo("电子");
        assertThat(lowConf.l1Confidence()).isEqualTo(0.30);
        assertThat(lowConf.lowConfidence()).isTrue();
        // PENDING 行：PASS 但未分类
        assertThat(view.items().get(2).l1Main()).isNull();
        assertThat(view.items().get(3).l1Main()).isEqualTo("银行");
    }

    @Test
    void listPaged_l0Noise_returnsOnlyNoiseWithDetail() {
        NewsItemsPagedView view = service.listPaged(filter(null, L0Result.NOISE, null), 1, 10);

        assertThat(view.items()).extracting(NewsItemView::id).containsExactly(n2);
        assertThat(view.total()).isEqualTo(1);
        assertThat(view.items().get(0).l0Result()).isEqualTo("NOISE");
        assertThat(view.items().get(0).l0Detail()).isEqualTo("推广");
    }

    @Test
    void listPaged_l0NearDup_resolvesMasterIdAndUrl() {
        NewsItemsPagedView view = service.listPaged(filter(null, L0Result.NEAR_DUP, null), 1, 10);

        assertThat(view.items()).extracting(NewsItemView::id).containsExactly(n3);
        assertThat(view.total()).isEqualTo(1);
        NewsItemView dup = view.items().get(0);
        assertThat(dup.l0Result()).isEqualTo("NEAR_DUP");
        assertThat(dup.l0Detail()).isEqualTo("hamming=3;edit=0.21");
        assertThat(dup.nearDupMasterId()).isEqualTo(n1);
        assertThat(dup.nearDupMasterUrl()).isEqualTo("https://example.com/n1");
    }

    @Test
    void listPaged_nearDupMasterCleaned_keepsIdButUrlNull() {
        // 主条被保留期清理：引用 id 留痕、url 解析为 null（前端「主条」链接降级隐藏，REQ 故事 3 场景 2）
        jdbcTemplate.update("DELETE FROM news_item WHERE id = ?", n1);

        NewsItemsPagedView view = service.listPaged(filter(null, L0Result.NEAR_DUP, null), 1, 10);

        assertThat(view.items()).hasSize(1);
        NewsItemView dup = view.items().get(0);
        assertThat(dup.id()).isEqualTo(n3);
        assertThat(dup.nearDupMasterId()).isEqualTo(n1);
        assertThat(dup.nearDupMasterUrl()).isNull();
    }

    @Test
    void listPaged_qMatchesTitleOrSummary_caseInsensitive_withLikeEscape() {
        // 标题命中（NEAR_DUP 行被缺省 PASS 过滤在外）
        assertThat(service.listPaged(filter("降息", L0Result.PASS, null), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n1);
        // 摘要命中
        assertThat(service.listPaged(filter("食品饮料", L0Result.PASS, null), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n4);
        // ASCII 大小写不敏感（SQLite LIKE 缺省语义）
        assertThat(service.listPaged(filter("iphone", null, null), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n6);
        // % 按字面匹配（转义后不构成通配：n2 摘要「推广50%特惠」不含字面「推广%特惠」→ 空）
        assertThat(service.listPaged(filter("推广%特惠", null, null), 1, 10).items()).isEmpty();
        // _ 按字面包含（标题「供应链_A_B」命中）
        assertThat(service.listPaged(filter("供应链_A_B", L0Result.PASS, null), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n6);
    }

    @Test
    void listPaged_l1FiltersByMainCategory_excludesPendingAndNoAnalysisRows() {
        assertThat(service.listPaged(filter(null, null, "银行"), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n1);
        assertThat(service.listPaged(filter(null, null, "电子"), 1, 10).items())
                .extracting(NewsItemView::id)
                .containsExactly(n6);
        // PENDING（n4）/无 analysis 行（n5）不在任何分类筛选结果中
        assertThat(service.listPaged(filter(null, null, "食品饮料"), 1, 10).items()).isEmpty();
    }

    @Test
    void listPaged_combinedFilters_andSemantics_withPaginationEcho() {
        // 四维组合（source + l0 + l1 + q）AND：结果 ⊆ 任一单条件结果
        NewsItemsPagedView combined = service.listPaged(filter("降息", L0Result.PASS, "银行"), 1, 10);
        assertThat(combined.items()).extracting(NewsItemView::id).containsExactly(n1);
        assertThat(combined.total()).isEqualTo(1);
        assertThat(service.listPaged(filter("降息", null, null), 1, 10).items())
                .extracting(NewsItemView::id)
                .contains(n1);

        // l0 不过滤（ALL 归一 null）+ 分页回归：6 条按 id DESC 分两页
        NewsItemsPagedView page1 = service.listPaged(filter(null, null, null), 1, 2);
        assertThat(page1.items()).extracting(NewsItemView::id).containsExactly(n6, n5);
        assertThat(page1.total()).isEqualTo(6);
        NewsItemsPagedView page2 = service.listPaged(filter(null, null, null), 2, 2);
        assertThat(page2.items()).extracting(NewsItemView::id).containsExactly(n4, n3);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.size()).isEqualTo(2);
    }

    @Test
    void listCursor_returnsAllStatesWithAnalysisFields_regression() {
        // 游标路径（信息流侧语义不动）：不按 l0 过滤，三态混合 id DESC；增量字段随行返回
        NewsItemsCursorView page = service.listCursor(sourceId, null, 10);

        assertThat(page.items())
                .extracting(NewsItemView::id)
                .containsExactly(n6, n5, n4, n3, n2, n1);
        assertThat(page.nextBeforeId()).isNull();
        NewsItemView dup = page.items().get(3);
        assertThat(dup.l0Result()).isEqualTo("NEAR_DUP");
        assertThat(dup.nearDupMasterId()).isEqualTo(n1);
        assertThat(dup.nearDupMasterUrl()).isEqualTo("https://example.com/n1");
        assertThat(page.items().get(4).l0Result()).isEqualTo("NOISE");
        assertThat(page.items().get(1).l0Result()).isEqualTo("PASS"); // 无行兜底

        // 游标续页信号回归
        NewsItemsCursorView first = service.listCursor(sourceId, null, 2);
        assertThat(first.nextBeforeId()).isEqualTo(n5);
        assertThat(service.listCursor(sourceId, first.nextBeforeId(), 2).items())
                .extracting(NewsItemView::id)
                .containsExactly(n4, n3);
    }
}
