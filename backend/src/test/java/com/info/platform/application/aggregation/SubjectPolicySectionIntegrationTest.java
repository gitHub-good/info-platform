package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.aggregation.PolicySectionView.Item;
import com.info.platform.application.valuation.FactorSnapshotService;
import com.info.platform.application.valuation.ValuationSettings;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.FeedFingerprint;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.valuation.FactorSnapshotRepository;
import com.info.platform.domain.valuation.IndustryAssociator;
import com.info.platform.domain.valuation.IndustryAssociator.Association;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 详情政策分区集成测试（V2.3-M23 T202，Gate 2 后半断言，方案 §7）：三类标的（industry NULL / 行业命中 / 标的直接命中）
 * <b>MISSING=0</b>（关联命中或宏观兜底二选其一）；matchType 徽章与命中路一致（双命中 ① 优先）； <b>关联集同源复算</b>——
 * SubjectIndustryAssociationReader 输出 vs FactorSnapshotService 全量投影（三路查询 + 同一转换 + 同一纯函数）逐集相等
 * （ADR-0062 裁决三「零第二套关联」的回归锁定）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SubjectPolicySectionIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 种子标的（真实标的池不存在——同源复算与回联断言可精确）。 */
    private static final String CODE_NULL_INDUSTRY = "SH998801";

    private static final String CODE_INDUSTRY_HIT = "SH998802";
    private static final String CODE_SUBJECT_HIT = "SH998803";

    @Autowired private SubjectPolicySectionService sectionService;
    @Autowired private SubjectIndustryAssociationReader associationReader;
    @Autowired private FactorSnapshotRepository factorRepository;
    @Autowired private ValuationSettings valuationSettings;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private InfoSourceRepository infoSourceRepository;
    @Autowired private FeedItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> seededNewsIds = new java.util.ArrayList<>();
    private long policySourceId;

    private long npSubject; // 政策源 DONE 监管·政策 matched[998802]——纯 ① 路
    private long npIndustry; // 政策源 DONE 银行 无回联——② 路（银行 ∈ 998802 关联集）
    private long npDouble; // 政策源 DONE 银行 matched[998802]——双命中（① 优先）
    private long npDirect; // 政策源 DONE 监管·政策 matched[998803]——998803 直接命中
    private long npMacro; // 政策源 DONE 监管·政策 无回联——998801 兜底段条目
    private long nlBacklink; // NEAR_DUP DONE 银行 matched[998802]——路 B 关联原料（非 PASS 不入 scope）

    @BeforeEach
    void setUp() {
        // 前置清残（subject_code 唯一——上一轮失败残留会令种子插入失败；常规清理在 @AfterEach）
        jdbcTemplate.update("DELETE FROM subject_master WHERE subject_code LIKE 'SH9988%'");
        jdbcTemplate.update("DELETE FROM event_item WHERE subjects LIKE '%SH9988%'");
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN"
                        + " (SELECT id FROM news_item WHERE title LIKE '%分区%条目' || '%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't202sec%'");

        InfoSource source =
                InfoSource.create(
                        "t202sec-pol",
                        "分区种子源",
                        "政策",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t202sec",
                        null,
                        60,
                        true,
                        false);
        infoSourceRepository.save(source);
        policySourceId = source.getId();

        subjectRepository.save(subject(CODE_NULL_INDUSTRY, null));
        subjectRepository.save(subject(CODE_INDUSTRY_HIT, "白酒Ⅱ")); // 东财板块 → 食品饮料（路 C）
        subjectRepository.save(subject(CODE_SUBJECT_HIT, null));

        Instant now = Instant.now();
        Instant recent = now.minusSeconds(3600);
        npSubject = insertItem("分区回联政策条目", recent);
        npIndustry = insertItem("银行监管政策条目", recent);
        npDouble = insertItem("银行回联双命中政策条目", recent);
        npDirect = insertItem("直接命中标的政策条目", recent);
        npMacro = insertItem("宏观兜底政策条目", recent);
        nlBacklink = insertItem("近重复回联原料条目", recent);

        String matched802 =
                "[{\"code\":\""
                        + CODE_INDUSTRY_HIT
                        + "\",\"name\":\"行业命中标的\",\"industry\":\"白酒\"}]";
        String matched803 =
                "[{\"code\":\"" + CODE_SUBJECT_HIT + "\",\"name\":\"直接命中标的\",\"industry\":null}]";
        insertAnalysis(npSubject, "PASS", "DONE", "监管·政策", null, matched802);
        insertAnalysis(npIndustry, "PASS", "DONE", "银行", null, null);
        insertAnalysis(npDouble, "PASS", "DONE", "银行", null, matched802);
        insertAnalysis(npDirect, "PASS", "DONE", "监管·政策", null, matched803);
        insertAnalysis(npMacro, "PASS", "DONE", "监管·政策", null, null);
        // 路 B 原料：DONE + matched（l0=NEAR_DUP → 不入政策 scope，只作关联派生输入）
        insertAnalysis(nlBacklink, "NEAR_DUP", "DONE", "银行", null, matched802);

        // 路 A 原料：事件回联（subjects 含 998802 → affected_industries 汽车）
        jdbcTemplate.update(
                "INSERT INTO event_item"
                        + " (news_id, event_type, summary, affected_industries, direction,"
                        + " importance, key_figures, subjects, quote, event_time, event_date,"
                        + " prompt_version, created_at, updated_at)"
                        + " VALUES (?, 'POLICY_RELEASE', ?, ?, 'BULLISH', 'HIGH', '[]', ?,"
                        + " NULL, NULL, ?, NULL, ?, ?)",
                npMacro,
                "新能源汽车产业政策发布",
                "[\"汽车\"]",
                "[{\"code\":\""
                        + CODE_INDUSTRY_HIT
                        + "\",\"name\":\"行业命中标的\",\"industry\":\"白酒\"}]",
                LocalDate.now(SHANGHAI).toString(),
                now.toString(),
                now.toString());
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM event_item WHERE subjects LIKE '%SH9988%'");
        if (!seededNewsIds.isEmpty()) {
            String ids = String.join(",", seededNewsIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM news_analysis WHERE news_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM news_item WHERE id IN (" + ids + ")");
            seededNewsIds.clear();
        }
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN"
                        + " (SELECT id FROM info_source WHERE source_code LIKE 't202sec%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't202sec%'");
        jdbcTemplate.update("DELETE FROM subject_master WHERE subject_code LIKE 'SH9988%'");
    }

    @Test
    void section_industryHitSubject_matchTypePerHitPath_missingZero() {
        // 行业命中标的（998802：路 A 汽车 + 路 B 银行 + 路 C 食品饮料）：①∪② 命中段非空、matchType 与命中路一致
        PolicySectionView section = sectionService.sectionOf(CODE_INDUSTRY_HIT);

        assertThat(section.items()).isNotEmpty(); // MISSING=0（构造性：关联命中段必达）
        assertThat(section.fallback()).isNull();
        assertThat(section.basis()).isEqualTo("policy-scope-v1");
        Map<Long, String> matchTypes =
                section.items().stream()
                        .collect(java.util.stream.Collectors.toMap(Item::id, Item::matchType));
        assertThat(matchTypes).containsEntry(npSubject, "SUBJECT");
        assertThat(matchTypes).containsEntry(npIndustry, "INDUSTRY");
        // 双命中（回联 + 行业）→ ① 优先（徽章唯一，可解释，REQ 拍板三）
        assertThat(matchTypes).containsEntry(npDouble, "SUBJECT");
        // 路 B 原料（NEAR_DUP）不入政策 scope——关联派生与分区条目两口径不串
        assertThat(matchTypes).doesNotContainKey(nlBacklink);
        // 关联集三路齐活：汽车（EVENT）/银行（NEWS_MAIN）/食品饮料（INDUSTRY_MEMBER）
        assertThat(associationReader.associationsOf(CODE_INDUSTRY_HIT))
                .extracting(Association::industry)
                .contains("汽车", "银行", "食品饮料");
    }

    @Test
    void section_subjectDirectHit_badgeSubject_missingZero() {
        // 标的直接命中（998803：industry NULL，仅回联命中 ① 路）
        PolicySectionView section = sectionService.sectionOf(CODE_SUBJECT_HIT);

        assertThat(section.items()).isNotEmpty();
        assertThat(section.items()).extracting(Item::id).contains(npDirect);
        assertThat(
                        section.items().stream()
                                .filter(i -> i.id() == npDirect)
                                .findFirst()
                                .orElseThrow()
                                .matchType())
                .isEqualTo("SUBJECT");
        assertThat(section.fallback()).isNull();
    }

    @Test
    void section_nullIndustryNoHit_macroFallback_missingZero() {
        // industry NULL 无命中（998801：无路 C 边、无回联、无事件）→ 宏观兜底段（口径明示、不冒充关联）
        PolicySectionView section = sectionService.sectionOf(CODE_NULL_INDUSTRY);

        assertThat(section.items()).isEmpty();
        assertThat(section.fallback()).isNotNull(); // MISSING=0（兜底段二选其一必达）
        assertThat(section.fallback().note()).isEqualTo(SubjectPolicySectionService.FALLBACK_NOTE);
        assertThat(section.fallback().items()).isNotEmpty();
        assertThat(section.fallback().items()).extracting(Item::id).contains(npMacro);
        assertThat(section.fallback().items())
                .allSatisfy(item -> assertThat(item.matchType()).isNull());
        assertThat(section.basis()).isEqualTo("policy-scope-v1");
    }

    @Test
    void association_sameSourceAsFullProjection_setBySetEqual() {
        // Gate 2 同源复算（ADR-0062 裁决三）：本读口输出 vs FactorSnapshotService 全量投影（三路全量查询 +
        // 同一转换函数 + 同一 IndustryAssociator 纯函数）逐集相等——「零第二套关联」回归锁定
        int windowDays = valuationSettings.params().assocWindowDays();
        LocalDate snapshotDate = LocalDate.now(SHANGHAI);
        Map<String, List<Association>> full =
                IndustryAssociator.associate(
                        FactorSnapshotService.eventLinks(
                                factorRepository.findEventsInWindow(
                                        snapshotDate.minusDays(windowDays - 1L).toString(),
                                        snapshotDate.toString())),
                        FactorSnapshotService.newsLinks(
                                factorRepository.findMatchedNewsInWindow(
                                        snapshotDate
                                                .minusDays(windowDays - 1L)
                                                .atStartOfDay(SHANGHAI)
                                                .toInstant()
                                                .toString(),
                                        snapshotDate
                                                .plusDays(1)
                                                .atStartOfDay(SHANGHAI)
                                                .toInstant()
                                                .toString())),
                        FactorSnapshotService.memberLinks(factorRepository.findIndustryMembers()),
                        snapshotDate,
                        windowDays);

        for (String code : List.of(CODE_NULL_INDUSTRY, CODE_INDUSTRY_HIT, CODE_SUBJECT_HIT)) {
            assertThat(associationReader.associationsOf(code))
                    .as("标的 %s 关联集与全量投影逐集相等", code)
                    .isEqualTo(full.getOrDefault(code, List.of()));
        }
    }

    // ---- 种子辅助 ----

    private static Subject subject(String code, String industry) {
        return Subject.reconstruct(
                null,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                "分区测试-" + code,
                Map.of(),
                industry,
                SubjectStatus.ENABLED,
                0L,
                null,
                null);
    }

    private long insertItem(String title, Instant publishedAt) {
        itemRepository.insertIgnoreBatch(
                List.of(
                        FeedItem.newOf(
                                policySourceId,
                                "ext-" + title,
                                title,
                                null,
                                "https://example.com/t202/" + title,
                                null,
                                publishedAt,
                                publishedAt,
                                FeedFingerprint.fingerprint(title + policySourceId, publishedAt))));
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE source_id = ? AND title = ?",
                        Long.class,
                        policySourceId,
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
}
