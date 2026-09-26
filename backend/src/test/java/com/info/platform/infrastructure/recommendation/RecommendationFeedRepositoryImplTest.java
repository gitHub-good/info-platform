package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * T133 仓储扩面集成测试：FEED 消费扫描（LEFT JOIN recommendation_card 去重 + 20s 缓冲 + 24h 补跑窗 + news 标题
 * join）、推送闸门数据面（findPendingByUser / countPushedSince 上海日界窗 / markPushed / markSkipped 条件迁移）、
 * 卡片流游标与四维筛选。t133f_ 前缀数据隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationFeedRepositoryImplTest {

    private static final long USER_ID = 731L;

    private static final long OTHER_USER_ID = 732L;

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private static final Instant CREATED_BEFORE = NOW.minusSeconds(20);

    private static final Instant CREATED_SINCE = NOW.minusSeconds(24 * 3600);

    @Autowired private RecommendationCardRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM recommendation_card WHERE user_id IN (?, ?)", USER_ID, OTHER_USER_ID);
        jdbcTemplate.update("DELETE FROM event_item WHERE id BETWEEN 8800 AND 8899");
        jdbcTemplate.update("DELETE FROM news_item WHERE id BETWEEN 8800 AND 8899");
        jdbcTemplate.update("DELETE FROM info_source WHERE id = 8801");
    }

    // ---- 种子工具 ----

    private void seedNewsAndEvent(long eventId, String createdAtIso) {
        seedNewsAndEvent(eventId, createdAtIso, true);
    }

    private void seedNewsAndEvent(long eventId, String createdAtIso, boolean withNews) {
        jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO info_source (id, source_code, name, category, adapter_type, endpoint,
                                         created_at, updated_at)
                VALUES (8801, 't133f_src', 'T133F源', '快讯', 'rss', 'https://example.com/rss',
                        '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                """);
        if (withNews) {
            jdbcTemplate.update(
                    """
                    INSERT OR IGNORE INTO news_item (id, source_id, title, published_at, fetched_at, fingerprint,
                                           created_at, updated_at)
                    VALUES (?, 8801, 'T133F原文标题', '2026-09-22T07:00:00Z', '2026-09-22T07:01:00Z', ?,
                            '2026-09-22T07:01:00Z', '2026-09-22T07:01:00Z')
                    """,
                    eventId,
                    "fp-t133f-" + eventId);
        }
        jdbcTemplate.update(
                """
                INSERT INTO event_item (id, news_id, event_type, summary, affected_industries,
                                        direction, importance, event_date, created_at, updated_at)
                VALUES (?, ?, 'BUYBACK_CHANGE', 'T133F事件摘要', '["食品饮料"]', 'BULLISH', 'HIGH',
                        '2026-09-22', ?, ?)
                """,
                eventId,
                withNews ? eventId : 9999L + eventId,
                createdAtIso,
                createdAtIso);
    }

    private void seedCard(
            long userId, long eventId, String pushStatus, String pushedAtIso, double recscore) {
        jdbcTemplate.update(
                """
                INSERT INTO recommendation_card (user_id, event_id, news_id, event_type, importance,
                                                 direction, level, industries, subjects, logic_chain,
                                                 logic_inputs, gen_method, recscore, basis, combo_key,
                                                 push_status, pushed_at, read, created_at, updated_at)
                VALUES (?, ?, 9999, 'BUYBACK_CHANGE', 'HIGH', 'BULLISH', 'P1', '["食品饮料"]', '[]',
                        'T133F逻辑链', NULL, 'TEMPLATE', ?, 'recscore-v1', 'BUYBACK_CHANGE|食品饮料',
                        ?, ?, 0, '2026-09-22T07:30:00Z', '2026-09-22T07:30:00Z')
                """,
                userId,
                eventId,
                recscore,
                pushStatus,
                pushedAtIso);
    }

    // ---- FEED 消费扫描（§3.2 裁决 2）----

    @Test
    void findUnconsumedEvents_returnsInWindowEventsWithNewsTitle() {
        // Arrange：窗口内事件（无卡）
        seedNewsAndEvent(8801L, "2026-09-22T07:00:00Z");

        // Act
        List<RecommendationCardRepository.FeedEvent> events =
                repository.findUnconsumedEvents(USER_ID, CREATED_BEFORE, CREATED_SINCE, 200);

        // Assert：标题 join 直出（P3 主题命中面 + 数字白名单来源）
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event().getId()).isEqualTo(8801L);
        assertThat(events.get(0).event().getEventType().name()).isEqualTo("BUYBACK_CHANGE");
        assertThat(events.get(0).newsTitle()).isEqualTo("T133F原文标题");
    }

    @Test
    void findUnconsumedEvents_dedupByCardOfSameUser_otherUserCardNotBlocking() {
        // Arrange：8801 已有本人卡（消费过）；8802 只有他人卡
        seedNewsAndEvent(8801L, "2026-09-22T07:00:00Z");
        seedNewsAndEvent(8802L, "2026-09-22T07:05:00Z");
        seedCard(USER_ID, 8801L, "PUSHED", "2026-09-22T07:06:00Z", 6.0);
        seedCard(OTHER_USER_ID, 8802L, "PUSHED", "2026-09-22T07:06:00Z", 6.0);

        // Act
        List<RecommendationCardRepository.FeedEvent> events =
                repository.findUnconsumedEvents(USER_ID, CREATED_BEFORE, CREATED_SINCE, 200);

        // Assert：LEFT JOIN 按 (event, user) 去重——本人已卡排除，他人卡不阻断
        assertThat(events).extracting(e -> e.event().getId()).containsExactly(8802L);
    }

    @Test
    void findUnconsumedEvents_respectsBufferAndScanWindow() {
        // Arrange：8811 落库不到 20s（竞态缓冲内）；8812 早于 24h 补跑窗；8803 恰在窗内
        seedNewsAndEvent(8811L, "2026-09-22T07:59:50Z"); // now-10s > createdBefore(now-20s)
        seedNewsAndEvent(8812L, "2026-09-21T07:59:59Z"); // 早于 createdSince(now-24h)
        seedNewsAndEvent(8803L, "2026-09-21T08:00:00Z"); // == createdSince 边界含

        // Act
        List<RecommendationCardRepository.FeedEvent> events =
                repository.findUnconsumedEvents(USER_ID, CREATED_BEFORE, CREATED_SINCE, 200);

        // Assert：只剩窗口内事件（缓冲排除 8811、补跑窗排除 8812、边界 8803 含）
        assertThat(events).extracting(e -> e.event().getId()).containsExactly(8803L);
    }

    @Test
    void findUnconsumedEvents_missingNews_titleNullNotFailing() {
        // Arrange：news 行缺失（LEFT JOIN 容错）
        seedNewsAndEvent(8804L, "2026-09-22T07:00:00Z", false);

        // Act
        List<RecommendationCardRepository.FeedEvent> events =
                repository.findUnconsumedEvents(USER_ID, CREATED_BEFORE, CREATED_SINCE, 200);

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0).newsTitle()).isNull();
    }

    // ---- 推送闸门数据面（§4.6）----

    @Test
    void findPendingByUser_returnsOnlyPendingOfOwner() {
        // Arrange
        seedCard(USER_ID, 8821L, "PENDING", null, 5.0);
        seedCard(USER_ID, 8822L, "PUSHED", "2026-09-22T07:00:00Z", 6.0);
        seedCard(USER_ID, 8823L, "SKIPPED_MUTED", null, 4.0);
        seedCard(OTHER_USER_ID, 8824L, "PENDING", null, 7.0);

        // Act
        List<RecommendationCard> pending = repository.findPendingByUser(USER_ID);

        // Assert
        assertThat(pending).extracting(RecommendationCard::getEventId).containsExactly(8821L);
    }

    @Test
    void countPushedSince_countsTodayPushedOnly() {
        // Arrange：当日已推 2 + 昨日推 1（窗外语义）
        seedCard(USER_ID, 8831L, "PUSHED", "2026-09-22T01:00:00Z", 5.0);
        seedCard(USER_ID, 8832L, "PUSHED", "2026-09-22T02:00:00Z", 6.0);
        seedCard(USER_ID, 8833L, "PUSHED", "2026-09-20T02:00:00Z", 4.0);
        seedCard(USER_ID, 8834L, "SKIPPED_QUOTA", null, 3.0);

        // Act：上海当日零点 = 2026-09-21T16:00:00Z
        long count = repository.countPushedSince(USER_ID, Instant.parse("2026-09-21T16:00:00Z"));

        // Assert：SILENT 态（SKIPPED_*）不占配额
        assertThat(count).isEqualTo(2);
    }

    @Test
    void markPushed_conditionalMigration_onlyFromPending() {
        // Arrange
        seedCard(USER_ID, 8841L, "PENDING", null, 5.0);
        seedCard(USER_ID, 8842L, "PUSHED", "2026-09-22T07:00:00Z", 6.0);

        // Act
        int first = repository.markPushed(idOfCard(USER_ID, 8841L), NOW);
        int second = repository.markPushed(idOfCard(USER_ID, 8841L), NOW);
        int alreadyPushed = repository.markPushed(idOfCard(USER_ID, 8842L), NOW);

        // Assert：条件 UPDATE WHERE push_status='PENDING'——已迁移/终态返回 0（幂等防线）
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(alreadyPushed).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT push_status FROM recommendation_card WHERE id = "
                                        + idOfCard(USER_ID, 8841L),
                                String.class))
                .isEqualTo("PUSHED");
    }

    @Test
    void markSkipped_conditionalMigration_quotaOrMuted() {
        // Arrange
        seedCard(USER_ID, 8851L, "PENDING", null, 5.0);

        // Act
        int migrated =
                repository.markSkipped(idOfCard(USER_ID, 8851L), CardPushStatus.SKIPPED_QUOTA);
        int again = repository.markSkipped(idOfCard(USER_ID, 8851L), CardPushStatus.SKIPPED_MUTED);

        // Assert：单向迁移——SKIPPED_* 后不再改判
        assertThat(migrated).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT push_status FROM recommendation_card WHERE id = "
                                        + idOfCard(USER_ID, 8851L),
                                String.class))
                .isEqualTo("SKIPPED_QUOTA");
    }

    // ---- 卡片流游标与四维筛选（§4.8）----

    @Test
    void findByUserCursor_filtersLevelEventTypeDirectionRead() {
        // Arrange：P1+BULLISH+未读 / P2+BEARISH+已读 / P1+NEUTRAL
        seedCard(USER_ID, 8861L, "PUSHED", "2026-09-22T07:00:00Z", 5.0);
        seedCard(USER_ID, 8862L, "PUSHED", "2026-09-22T07:00:00Z", 4.0);
        jdbcTemplate.update(
                "UPDATE recommendation_card SET level='P2', direction='BEARISH', read=1 WHERE user_id = ? AND event_id = 8862",
                USER_ID);
        seedCard(USER_ID, 8863L, "PENDING", null, 3.0);
        jdbcTemplate.update(
                "UPDATE recommendation_card SET direction='NEUTRAL' WHERE user_id = ? AND event_id = 8863",
                USER_ID);
        seedCard(OTHER_USER_ID, 8864L, "PUSHED", "2026-09-22T07:00:00Z", 9.0);

        // Act + Assert：level 筛选
        assertThat(
                        repository
                                .findByUserCursor(
                                        USER_ID,
                                        new RecommendationCardRepository.CardFilter(
                                                RecLevel.P2, null, null, null),
                                        null,
                                        20)
                                .size())
                .isEqualTo(1);
        // 组合筛选：P1 + BULLISH + 未读
        assertThat(
                        repository
                                .findByUserCursor(
                                        USER_ID,
                                        new RecommendationCardRepository.CardFilter(
                                                RecLevel.P1,
                                                com.info.platform.domain.analysis.EventType
                                                        .BUYBACK_CHANGE,
                                                com.info.platform.domain.analysis.Direction.BULLISH,
                                                false),
                                        null,
                                        20)
                                .size())
                .isEqualTo(1);
        // read 维度
        assertThat(
                        repository
                                .findByUserCursor(
                                        USER_ID,
                                        new RecommendationCardRepository.CardFilter(
                                                null, null, null, true),
                                        null,
                                        20)
                                .size())
                .isEqualTo(1);
        // 行级权限：他人卡不可见
        assertThat(
                        repository.countByUser(
                                USER_ID, RecommendationCardRepository.CardFilter.unfiltered()))
                .isEqualTo(3);
    }

    @Test
    void findByUserCursor_beforeIdPagination_desc() {
        // Arrange：三张卡 id 递增插入
        seedCard(USER_ID, 8871L, "PUSHED", "2026-09-22T07:00:00Z", 5.0);
        seedCard(USER_ID, 8872L, "PUSHED", "2026-09-22T07:00:00Z", 5.0);
        seedCard(USER_ID, 8873L, "PUSHED", "2026-09-22T07:00:00Z", 5.0);

        // Act
        List<RecommendationCard> page1 =
                repository.findByUserCursor(
                        USER_ID, RecommendationCardRepository.CardFilter.unfiltered(), null, 2);
        List<RecommendationCard> page2 =
                repository.findByUserCursor(
                        USER_ID,
                        RecommendationCardRepository.CardFilter.unfiltered(),
                        page1.get(page1.size() - 1).getId(),
                        2);

        // Assert：id DESC + beforeId 游标续页
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).getEventId()).isEqualTo(8873L);
        assertThat(page2).extracting(RecommendationCard::getEventId).containsExactly(8871L);
    }

    @Test
    void findById_returnsCard() {
        // Arrange
        seedCard(USER_ID, 8881L, "PUSHED", "2026-09-22T07:00:00Z", 5.0);
        long cardId = idOfCard(USER_ID, 8881L);

        // Act + Assert：owner 校验在应用层，仓储按主键取
        assertThat(repository.findById(cardId)).isPresent();
        assertThat(repository.findById(424242L)).isEmpty();
        assertThat(repository.findById(cardId).get().getGenMethod())
                .isEqualTo(CardGenMethod.TEMPLATE);
    }

    private long idOfCard(long userId, long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM recommendation_card WHERE user_id = ? AND event_id = ?",
                Long.class,
                userId,
                eventId);
    }
}
