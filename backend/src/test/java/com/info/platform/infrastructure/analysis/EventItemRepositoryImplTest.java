package com.info.platform.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

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
                        EventType.POLICY_RELEASE, null, Importance.HIGH, Direction.BULLISH);

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
                new EventItemRepository.EventStreamFilter(null, "银行", null, null);

        assertThat(repository.findStreamItems(bankFilter, null, 20))
                .extracting(row -> row.event().getId())
                .containsExactly(bank); // 「非银金融」不因子串「银行」误命中
        assertThat(repository.countStreamItems(bankFilter)).isEqualTo(1L);

        EventItemRepository.EventStreamFilter nonBankFilter =
                new EventItemRepository.EventStreamFilter(null, "非银金融", null, null);
        assertThat(repository.countStreamItems(nonBankFilter)).isEqualTo(1L);
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
}
