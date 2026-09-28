package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.recommendation.CardGenMethod;
import com.info.platform.domain.recommendation.CardPushStatus;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.recommendation.RecommendationCard;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RecommendationCardRepository 集成测试（T131，方案 §4.1/§4.5）：V26 三表建出、建卡 INSERT OR IGNORE 幂等
 * （UNIQUE(user_id,event_id)——同用户同事件二卡收敛 0）、JSON 列往返（industries/subjects）、按用户+事件回读。 t131_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationCardRepositoryImplTest {

    @Autowired private RecommendationCardRepositoryImpl repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM recommendation_card WHERE user_id = 701");
    }

    private static RecommendationCard card(long eventId, double recscore) {
        return RecommendationCard.create(
                701L,
                eventId,
                8001L + eventId,
                "POLICY_RELEASE",
                "HIGH",
                "BULLISH",
                RecLevel.P2,
                List.of("电子", "计算机"),
                List.of(
                        new RecommendationCard.CardSubject("SH600519", "贵州茅台", "食品饮料", true),
                        new RecommendationCard.CardSubject("SZ300750", "宁德时代", "电力设备", false)),
                "政策利好半导体行业——你关注的电子行业受该事件影响。",
                "{\"level\":\"P2\"}",
                CardGenMethod.TEMPLATE,
                null,
                recscore,
                "recscore-v1:lvl=3|2|1;imp=2|1;pf=1+0.25*max(heat/5,theme=0.6)",
                "POLICY_RELEASE|电子");
    }

    @Test
    void migration_v26_threeTablesExist() {
        for (String table :
                List.of("recommendation_card", "recommendation_feedback", "recommendation_mute")) {
            Integer count =
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).isZero();
        }
    }

    @Test
    void insertIgnore_firstInsertSucceeds_duplicateReturnsZero() {
        // Arrange
        RecommendationCard first = card(101L, 4.6);

        // Act
        int insertedFirst = repository.insertIgnore(first);
        int insertedAgain = repository.insertIgnore(card(101L, 9.9));

        // Assert：一事件一卡幂等（重复消费直返 0，先入者胜出）
        assertThat(insertedFirst).isEqualTo(1);
        assertThat(insertedAgain).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM recommendation_card WHERE user_id = 701 AND event_id = 101",
                                Integer.class))
                .isEqualTo(1);
        // 先入者的 recscore 不被二次写入覆盖
        assertThat(repository.findByUserAndEvent(701L, 101L)).isPresent();
        assertThat(repository.findByUserAndEvent(701L, 101L).get().getRecscore()).isEqualTo(4.6);
    }

    @Test
    void insertIgnore_differentUserOrEvent_bothStored() {
        // Arrange/Act：同事件不同用户、同用户不同事件均落卡
        int a = repository.insertIgnore(card(201L, 4.0));
        jdbcTemplate.update(
                "UPDATE recommendation_card SET user_id = 702 WHERE user_id = 701 AND event_id = 201");
        int b = repository.insertIgnore(card(201L, 5.0));
        int c = repository.insertIgnore(card(202L, 5.0));

        // Assert
        assertThat(a).isEqualTo(1);
        assertThat(b).isEqualTo(1);
        assertThat(c).isEqualTo(1);
        assertThat(repository.findByUserAndEvent(702L, 201L)).isPresent();
        assertThat(repository.findByUserAndEvent(701L, 201L)).isPresent();
        assertThat(repository.findByUserAndEvent(701L, 202L)).isPresent();
        jdbcTemplate.update("DELETE FROM recommendation_card WHERE user_id = 702");
    }

    @Test
    void insertIgnore_roundTripsJsonColumnsAndDefaults() {
        // Arrange/Act
        repository.insertIgnore(card(301L, 4.6));
        RecommendationCard loaded = repository.findByUserAndEvent(701L, 301L).orElseThrow();

        // Assert：JSON 列往返完整；建卡缺省 push_status=PENDING/read=0/adopted=0
        assertThat(loaded.getLevel()).isEqualTo(RecLevel.P2);
        assertThat(loaded.getIndustries()).containsExactly("电子", "计算机");
        assertThat(loaded.getSubjects()).hasSize(2);
        assertThat(loaded.getSubjects().get(0).code()).isEqualTo("SH600519");
        assertThat(loaded.getSubjects().get(0).inWatchlist()).isTrue();
        assertThat(loaded.getSubjects().get(1).inWatchlist()).isFalse();
        assertThat(loaded.getLogicChain()).contains("电子");
        assertThat(loaded.getGenMethod()).isEqualTo(CardGenMethod.TEMPLATE);
        assertThat(loaded.getComboKey()).isEqualTo("POLICY_RELEASE|电子");
        assertThat(loaded.getPushStatus()).isEqualTo(CardPushStatus.PENDING);
        assertThat(loaded.isRead()).isFalse();
        assertThat(loaded.isAdopted()).isFalse();
        assertThat(loaded.getNewsId()).isEqualTo(8001L + 301L);
    }

    @Test
    void findByUserAndEvent_missing_returnsEmpty() {
        assertThat(repository.findByUserAndEvent(701L, 424242L)).isEmpty();
    }

    // ---- T220（M25 V3.0）：page/size 页码模式（LIMIT/OFFSET，id DESC 同序）----

    @Test
    void findByUserPage_offsetWindows_idDesc_disjointAndBeyondLastEmpty() {
        // Arrange：5 张卡 → size=2 三页 + 越界第 4 页空列表（offset 语义 200 + 如实回显）
        for (long eventId = 501L; eventId <= 505L; eventId++) {
            repository.insertIgnore(card(eventId, 4.0));
        }
        java.util.List<Long> allIds =
                jdbcTemplate.queryForList(
                        "SELECT id FROM recommendation_card WHERE user_id = 701"
                                + " ORDER BY id DESC",
                        Long.class);

        // Act/Assert：id DESC 窗口切页，页间不重叠不遗漏
        assertThat(
                        repository.findByUserPage(
                                701L, RecommendationCardRepository.CardFilter.unfiltered(), 1, 2))
                .extracting(RecommendationCard::getId)
                .containsExactly(allIds.get(0), allIds.get(1));
        assertThat(
                        repository.findByUserPage(
                                701L, RecommendationCardRepository.CardFilter.unfiltered(), 2, 2))
                .extracting(RecommendationCard::getId)
                .containsExactly(allIds.get(2), allIds.get(3));
        assertThat(
                        repository.findByUserPage(
                                701L, RecommendationCardRepository.CardFilter.unfiltered(), 3, 2))
                .extracting(RecommendationCard::getId)
                .containsExactly(allIds.get(4));
        assertThat(
                        repository.findByUserPage(
                                701L, RecommendationCardRepository.CardFilter.unfiltered(), 4, 2))
                .isEmpty(); // 越界页
        assertThat(
                        repository.countByUser(
                                701L, RecommendationCardRepository.CardFilter.unfiltered()))
                .isEqualTo(5L);
    }

    @Test
    void findByUserPage_consistentWithCursorAndFilterScoped() {
        // Arrange：本用户 2 张 P1 + 1 张 P2；他人 1 张（行级隔离）
        repository.insertIgnore(card(601L, 4.0)); // P2（card() 缺省）
        jdbcTemplate.update(
                "INSERT INTO recommendation_card (user_id, event_id, news_id, event_type,"
                        + " importance, direction, level, industries, subjects, logic_chain,"
                        + " logic_inputs, gen_method, prompt_version, recscore, basis, combo_key,"
                        + " push_status, pushed_at, read, adopted, created_at, updated_at)"
                        + " VALUES (701, 602, 8602, 'POLICY_RELEASE', 'HIGH', 'BULLISH', 'P1', '[]',"
                        + " '[]', '链', '{}', 'TEMPLATE', NULL, 4.0, 'b', 'ck', 'PUSHED', NULL, 0, 0,"
                        + " '2026-09-22T08:00:00Z', '2026-09-22T08:00:00Z'),"
                        + " (701, 603, 8603, 'POLICY_RELEASE', 'HIGH', 'BULLISH', 'P1', '[]',"
                        + " '[]', '链', '{}', 'TEMPLATE', NULL, 4.0, 'b', 'ck', 'PUSHED', NULL, 0, 0,"
                        + " '2026-09-22T08:00:00Z', '2026-09-22T08:00:00Z'),"
                        + " (799, 604, 8604, 'POLICY_RELEASE', 'HIGH', 'BULLISH', 'P1', '[]',"
                        + " '[]', '链', '{}', 'TEMPLATE', NULL, 4.0, 'b', 'ck', 'PUSHED', NULL, 0, 0,"
                        + " '2026-09-22T08:00:00Z', '2026-09-22T08:00:00Z')");
        RecommendationCardRepository.CardFilter p1Only =
                new RecommendationCardRepository.CardFilter(RecLevel.P1, null, null, null);

        // Act/Assert：P1 筛选 total=2，第 1 页 = 游标首页（同序对照）；他人卡不计入
        assertThat(repository.countByUser(701L, p1Only)).isEqualTo(2L);
        assertThat(repository.findByUserPage(701L, p1Only, 1, 1))
                .extracting(RecommendationCard::getId)
                .containsExactlyElementsOf(
                        repository.findByUserCursor(701L, p1Only, null, 1).stream()
                                .map(RecommendationCard::getId)
                                .toList());
        assertThat(repository.findByUserPage(701L, p1Only, 2, 1))
                .extracting(RecommendationCard::getId)
                .hasSize(1);
        jdbcTemplate.update("DELETE FROM recommendation_card WHERE user_id = 799");
    }
}
