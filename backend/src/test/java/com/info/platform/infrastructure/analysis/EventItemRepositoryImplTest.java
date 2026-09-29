package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItemRepository;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.feed.AdapterType;
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
 * EventItemRepository 事件流查询集成测试（T127，方案 §4.8/§4.10）：四维筛选矩阵（type/industry/importance/ direction）、id
 * DESC 游标分页、无筛选 total = 全量对账、行业 LIKE 引号定界（银行 vs 非银金融）、卡片 join news_item 标题/链接、JSON
 * 列（figures/subjects/industries）回读。V23 表由 Flyway 内存库建出。t127_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class EventItemRepositoryImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired private EventItemRepository repository;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long sourceId;

    @BeforeEach
    void setUpSource() {
        InfoSource source =
                InfoSource.create(
                        "t127_events",
                        "t127_events",
                        "快讯",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t127",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM event_item WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't127%'))");
        jdbcTemplate.update(
                "DELETE FROM news_item WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't127%')");
        jdbcTemplate.update(
                "DELETE FROM source_poll_state WHERE source_id IN "
                        + "(SELECT id FROM info_source WHERE source_code LIKE 't127%')");
        jdbcTemplate.update("DELETE FROM info_source WHERE source_code LIKE 't127%'");
    }

    /** 造一条事件（affectedIndustries 为 JSON 文本）。 */
    private long seedEvent(
            EventType type,
            Direction direction,
            Importance importance,
            String affectedJson,
            String figuresJson,
            String subjectsJson,
            String quote,
            String title) {
        String stamp = String.valueOf(System.nanoTime());
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                sourceId,
                "t127_" + stamp,
                title,
                "摘要-" + title,
                "https://example.com/n/" + stamp,
                NOW.toString(),
                NOW.toString(),
                "fp-" + stamp,
                NOW.toString(),
                NOW.toString());
        Long newsId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM news_item WHERE title = ?", Long.class, title);
        jdbcTemplate.update(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, key_figures, subjects, quote, event_time,"
                        + " event_date, prompt_version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-22', 'v1.0', ?, ?)",
                newsId,
                type.name(),
                "事件摘要-" + title,
                affectedJson,
                direction.name(),
                importance.name(),
                figuresJson,
                subjectsJson,
                quote,
                NOW.toString(),
                NOW.toString(),
                NOW.toString());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM event_item WHERE news_id = ?", Long.class, newsId);
    }

    @Test
    void findStreamItems_noFilter_idDescCursorPagination() {
        long idA =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "标题A");
        long idB =
                seedEvent(
                        EventType.EARNINGS_FORECAST,
                        Direction.NEUTRAL,
                        Importance.LOW,
                        "[\"钢铁\"]",
                        "[]",
                        "[]",
                        null,
                        "标题B");
        long idC =
                seedEvent(
                        EventType.MA_MERGER,
                        Direction.BEARISH,
                        Importance.MEDIUM,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "标题C");

        EventItemRepository.EventStreamFilter unfiltered =
                EventItemRepository.EventStreamFilter.unfiltered();

        List<EventItemRepository.EventStreamItem> firstPage =
                repository.findStreamItems(unfiltered, null, 2);

        assertThat(firstPage)
                .extracting(row -> row.event().getId())
                .containsExactly(idC, idB); // id DESC
        assertThat(firstPage.get(0).newsTitle()).isEqualTo("标题C"); // join news_item
        assertThat(firstPage.get(0).newsUrl()).startsWith("https://example.com/n/");

        List<EventItemRepository.EventStreamItem> secondPage =
                repository.findStreamItems(unfiltered, idB, 2);
        assertThat(secondPage).extracting(row -> row.event().getId()).containsExactly(idA);

        assertThat(repository.countStreamItems(unfiltered)).isEqualTo(3L);
    }

    @Test
    void findStreamItems_typeImportanceDirectionFilters() {
        long policyHighBull =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "命中");
        seedEvent(
                EventType.POLICY_RELEASE,
                Direction.BEARISH,
                Importance.HIGH,
                "[\"银行\"]",
                "[]",
                "[]",
                null,
                "方向不符");
        seedEvent(
                EventType.EARNINGS_FORECAST,
                Direction.BULLISH,
                Importance.HIGH,
                "[\"银行\"]",
                "[]",
                "[]",
                null,
                "类型不符");
        seedEvent(
                EventType.POLICY_RELEASE,
                Direction.BULLISH,
                Importance.LOW,
                "[\"银行\"]",
                "[]",
                "[]",
                null,
                "重要度不符");

        EventItemRepository.EventStreamFilter filter =
                new EventItemRepository.EventStreamFilter(
                        EventType.POLICY_RELEASE, null, Importance.HIGH, Direction.BULLISH, null);

        assertThat(repository.findStreamItems(filter, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactly(policyHighBull);
        assertThat(repository.countStreamItems(filter)).isEqualTo(1L);
    }

    @Test
    void findStreamItems_industryFilter_quotedDelimiterNoSubstringMisMatch() {
        long bank =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "银行事件");
        long nonBank =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"非银金融\"]",
                        "[]",
                        "[]",
                        null,
                        "非银事件");

        EventItemRepository.EventStreamFilter bankFilter =
                new EventItemRepository.EventStreamFilter(null, "银行", null, null, null);

        assertThat(repository.findStreamItems(bankFilter, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactly(bank); // 「非银金融」不因子串「银行」误命中
        assertThat(repository.countStreamItems(bankFilter)).isEqualTo(1L);

        EventItemRepository.EventStreamFilter nonBankFilter =
                new EventItemRepository.EventStreamFilter(null, "非银金融", null, null, null);
        assertThat(repository.countStreamItems(nonBankFilter)).isEqualTo(1L);
    }

    // ---- M29 P1-01 回归（方案 §5.4）：market 过滤维（subjects code 前缀——事件关联标的含该市场标的）----

    @Test
    void findStreamItems_marketFilter_subjectCodePrefix() {
        // Arrange：A股标的事件 / 港股标的事件 / 美股标的事件 / 混合标的（A+HK）/ 未回联事件 / 空 subjects 事件
        long aShare =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[{\"code\":\"SZ000001\",\"name\":\"平安银行\",\"industry\":\"银行\"}]",
                        null,
                        "A股标的事件");
        long hk =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"软件服务\"]",
                        "[]",
                        "[{\"code\":\"HK00700\",\"name\":\"腾讯控股\",\"industry\":\"软件服务\"}]",
                        null,
                        "港股标的事件");
        long us =
                seedEvent(
                        EventType.EARNINGS_FORECAST,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"通信与电信\"]",
                        "[]",
                        "[{\"code\":\"USQCOM\",\"name\":\"高通\",\"industry\":\"通信与电信\"}]",
                        null,
                        "美股标的事件");
        long mixed =
                seedEvent(
                        EventType.MA_MERGER,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[{\"code\":\"SH601869\",\"name\":\"长飞光纤\",\"industry\":null},"
                                + "{\"code\":\"HK00700\",\"name\":\"腾讯控股\",\"industry\":\"互联网\"}]",
                        null,
                        "A港混合标的事件");
        seedEvent(
                EventType.POLICY_RELEASE,
                Direction.BEARISH,
                Importance.LOW,
                "[\"宏观\"]",
                "[]",
                "[{\"code\":null,\"name\":\"某未回联公司\",\"industry\":null}]",
                null,
                "未回联标的事件");
        seedEvent(
                EventType.POLICY_RELEASE,
                Direction.NEUTRAL,
                Importance.LOW,
                "[\"宏观\"]",
                "[]",
                "[]",
                null,
                "无标的事件");

        // Act/Assert：market=HK → 仅港股标的命中（含 A+HK 混合事件——「含该市场标的」语义）
        EventItemRepository.EventStreamFilter hkFilter =
                new EventItemRepository.EventStreamFilter(null, null, null, null, Market.HK);
        assertThat(repository.findStreamItems(hkFilter, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactlyInAnyOrder(hk, mixed);
        assertThat(repository.countStreamItems(hkFilter)).isEqualTo(2L);

        // Act/Assert：market=US → 仅美股标的命中
        EventItemRepository.EventStreamFilter usFilter =
                new EventItemRepository.EventStreamFilter(null, null, null, null, Market.US);
        assertThat(repository.findStreamItems(usFilter, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactly(us);
        assertThat(repository.countStreamItems(usFilter)).isEqualTo(1L);

        // Act/Assert：market 维 null（缺省/A_SHARE 归一后）→ 全量 6 条零回归
        assertThat(repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered()))
                .isEqualTo(6L);

        // Act/Assert：market 与 industry 组合 → HK + 软件服务（跨市场重名行业由 market 消歧——A股银行事件不混入）
        EventItemRepository.EventStreamFilter hkSoftware =
                new EventItemRepository.EventStreamFilter(null, "软件服务", null, null, Market.HK);
        assertThat(repository.findStreamItems(hkSoftware, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactly(hk);
    }

    @Test
    void findStreamItems_mapsFullCardFields() {
        seedEvent(
                EventType.POLICY_RELEASE,
                Direction.BULLISH,
                Importance.HIGH,
                "[\"银行\",\"钢铁\"]",
                "[{\"label\":\"存款准备金率\",\"value\":\"0.5\",\"unit\":\"pct\"}]",
                "[{\"code\":\"SZ000001\",\"name\":\"平安银行\",\"industry\":\"银行\"},"
                        + "{\"code\":null,\"name\":\"未回联公司\",\"industry\":null}]",
                "下调金融机构存款准备金率 0.5 个百分点",
                "全字段事件");

        EventItemRepository.EventStreamItem row =
                repository
                        .findStreamItems(
                                EventItemRepository.EventStreamFilter.unfiltered(), null, 20)
                        .get(0);

        assertThat(row.event().getAffectedIndustries()).containsExactly("银行", "钢铁");
        assertThat(row.event().getKeyFigures()).hasSize(1);
        assertThat(row.event().getKeyFigures().get(0).label()).isEqualTo("存款准备金率");
        assertThat(row.event().getKeyFigures().get(0).value()).isEqualTo("0.5");
        assertThat(row.event().getSubjects()).hasSize(2);
        assertThat(row.event().getSubjects().get(0).code()).isEqualTo("SZ000001");
        assertThat(row.event().getSubjects().get(1).code()).isNull();
        assertThat(row.event().getQuote()).isEqualTo("下调金融机构存款准备金率 0.5 个百分点");
        assertThat(row.event().getEventTime()).isEqualTo(NOW);
        assertThat(row.newsTitle()).isEqualTo("全字段事件");
        assertThat(row.newsUrl()).startsWith("https://example.com/n/");
    }

    @Test
    void countStreamItems_emptyTable_zero() {
        assertThat(repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered()))
                .isZero();
        assertThat(
                        repository.findStreamItems(
                                EventItemRepository.EventStreamFilter.unfiltered(), null, 20))
                .isEmpty();
    }

    // ---- T220（M25 V3.0）：page/size 页码模式（LIMIT/OFFSET，id DESC 同序）----

    @Test
    void findStreamItemsPaged_offsetWindows_idDesc_disjointAndBeyondLastEmpty() {
        // Arrange：5 条事件 → size=2 三页 + 越界第 4 页空列表（offset 语义 200 + 如实回显）
        long idA =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "页码A");
        long idB =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "页码B");
        long idC =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "页码C");
        long idD =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "页码D");
        long idE =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "页码E");
        EventItemRepository.EventStreamFilter unfiltered =
                EventItemRepository.EventStreamFilter.unfiltered();

        // Act/Assert：id DESC 窗口切页，页间不重叠不遗漏
        assertThat(repository.findStreamItemsPaged(unfiltered, 1, 2))
                .extracting(row -> row.event().getId())
                .containsExactly(idE, idD);
        assertThat(repository.findStreamItemsPaged(unfiltered, 2, 2))
                .extracting(row -> row.event().getId())
                .containsExactly(idC, idB);
        assertThat(repository.findStreamItemsPaged(unfiltered, 3, 2))
                .extracting(row -> row.event().getId())
                .containsExactly(idA);
        assertThat(repository.findStreamItemsPaged(unfiltered, 4, 2)).isEmpty(); // 越界页
        assertThat(repository.countStreamItems(unfiltered)).isEqualTo(5L);
    }

    @Test
    void findStreamItemsPaged_consistentWithCursorFirstPage() {
        // 页码+游标模式并存回归：同筛选同序（ORDER BY id DESC 单一全序），第 1 页 = 游标首页
        long idA =
                seedEvent(
                        EventType.POLICY_RELEASE,
                        Direction.BULLISH,
                        Importance.HIGH,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "对照A");
        long idB =
                seedEvent(
                        EventType.EARNINGS_FORECAST,
                        Direction.NEUTRAL,
                        Importance.MEDIUM,
                        "[\"钢铁\"]",
                        "[]",
                        "[]",
                        null,
                        "对照B");
        long idC =
                seedEvent(
                        EventType.MA_MERGER,
                        Direction.BEARISH,
                        Importance.LOW,
                        "[\"银行\"]",
                        "[]",
                        "[]",
                        null,
                        "对照C");
        EventItemRepository.EventStreamFilter unfiltered =
                EventItemRepository.EventStreamFilter.unfiltered();

        assertThat(repository.findStreamItemsPaged(unfiltered, 1, 2))
                .extracting(row -> row.event().getId())
                .containsExactlyElementsOf(
                        repository.findStreamItems(unfiltered, null, 2).stream()
                                .map(row -> row.event().getId())
                                .toList()); // [idC, idB]
        assertThat(repository.findStreamItemsPaged(unfiltered, 2, 2))
                .extracting(row -> row.event().getId())
                .containsExactly(idA);
    }

    @Test
    void findStreamItemsPaged_deepOffset_measuredPerformance() {
        // offset 实测留档（沿 M9 §3.2 深分页实测先例）：600 行量级最深页窗口查询毫秒级
        seedBulkEvents(600);
        int size = 20;
        long total =
                repository.countStreamItems(EventItemRepository.EventStreamFilter.unfiltered());
        int deepestPage = (int) (total / size); // 最深有数据页（offset ≈ total-size）

        long startedAt = System.nanoTime();
        List<EventItemRepository.EventStreamItem> deepest =
                repository.findStreamItemsPaged(
                        EventItemRepository.EventStreamFilter.unfiltered(), deepestPage, size);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(deepest).isNotEmpty();
        assertThat(deepest).hasSizeLessThanOrEqualTo(size);
        // 护栏断言（宽松上界防 CI 抖动；实测数值由 stdout 留档，M9 护栏阈值 100ms P95 对照）
        assertThat(elapsedMs).isLessThan(500L);
        System.out.printf(
                "[T220 offset 实测] event_item %d 行最深页(page=%d, size=%d) 返回 %d 行，耗时 %dms%n",
                total, deepestPage, size, deepest.size(), elapsedMs);
    }

    /** 批量直插事件行（性能实测造数：news_item + event_item 各 N 行，避开 seedEvent 逐行回查）。 */
    private void seedBulkEvents(int count) {
        String now = NOW.toString();
        String stamp = String.valueOf(System.nanoTime());
        java.util.List<Object[]> newsRows = new java.util.ArrayList<>(count);
        java.util.List<Object[]> eventRows = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String title = "t220 批量-" + stamp + "-" + i;
            newsRows.add(
                    new Object[] {
                        sourceId,
                        "t220_" + stamp + "_" + i,
                        title,
                        "摘要",
                        "https://example.com/t220/" + i,
                        now,
                        now,
                        "fp-t220-" + stamp + "-" + i,
                        now,
                        now
                    });
            eventRows.add(new Object[] {now, now, now, title});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                newsRows);
        jdbcTemplate.batchUpdate(
                "INSERT INTO event_item (news_id, event_type, summary, affected_industries,"
                        + " direction, importance, key_figures, subjects, quote, event_time,"
                        + " event_date, prompt_version, created_at, updated_at)"
                        + " SELECT id, 'POLICY_RELEASE', 't220 性能造数', '[\"银行\"]', 'BULLISH',"
                        + " 'HIGH', '[]', '[]', NULL, ?, '2026-09-22', 'v1.0', ?, ?"
                        + " FROM news_item WHERE title = ?",
                eventRows);
    }
}
