package com.info.platform.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.recommendation.MuteStatus;
import com.info.platform.domain.recommendation.RecommendationMute;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 降噪组合仓储集成测试（T133 数据面 / T134 写路径前置落地）：findActiveByUserAndCombo 拦截语义（ACTIVE 且未到期才命中；
 * 到期/LIFTED/他人组合返回空）、upsert（新建 + reactivate 续期覆盖）、liftByUserAndCombo 条件撤销。t133m_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationMuteRepositoryImplTest {

    private static final long USER_ID = 741L;

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private static final String COMBO = "BUYBACK_CHANGE|食品饮料";

    @Autowired private RecommendationMuteRepository repository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM recommendation_mute WHERE user_id = ?", USER_ID);
    }

    @Test
    void findActive_activeAndNotExpired_hits() {
        // Arrange
        repository.upsert(RecommendationMute.create(USER_ID, COMBO, 7, NOW));

        // Act
        Optional<RecommendationMute> found = repository.findActiveByUserAndCombo(USER_ID, COMBO);

        // Assert：ACTIVE 且 now < muted_until 才拦截
        assertThat(found).isPresent();
        assertThat(found.get().isMuting(NOW.plusSeconds(3600))).isTrue();
        assertThat(found.get().getMuteDays()).isEqualTo(7);
    }

    @Test
    void findActive_expiredOrLiftedOrForeign_returnsEmpty() {
        // Arrange：到期行（ACTIVE 但 muted_until 已过）
        repository.upsert(
                RecommendationMute.create(USER_ID, COMBO, 7, NOW.minusSeconds(8 * 86400)));
        // Assert 到期不拦截
        assertThat(repository.findActiveByUserAndCombo(USER_ID, COMBO).map(m -> m.isMuting(NOW)))
                .contains(false);

        // Arrange：撤销后（LIFTED 常驻行保留）
        repository.upsert(RecommendationMute.create(USER_ID, COMBO, 7, NOW));
        repository.liftByUserAndCombo(USER_ID, COMBO);

        // Assert：LIFTED 后查询不再命中拦截语义
        assertThat(repository.findActiveByUserAndCombo(USER_ID, COMBO)).isEmpty();
        // 他人组合不串扰
        assertThat(repository.findActiveByUserAndCombo(USER_ID + 1, COMBO)).isEmpty();
    }

    @Test
    void upsert_existingCombo_overwritesAsReactivate() {
        // Arrange：先建 7 天，再 upsert 30 天（升级静默同款写路径，T134 消费）
        repository.upsert(RecommendationMute.create(USER_ID, COMBO, 7, NOW));
        RecommendationMute escalated =
                repository.upsert(RecommendationMute.create(USER_ID, COMBO, 30, NOW));

        // Assert：UNIQUE(user_id, combo_key) 收敛单行，参数覆盖（mute_days/muted_until）
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM recommendation_mute WHERE user_id = ?",
                                Integer.class,
                                USER_ID))
                .isEqualTo(1);
        assertThat(escalated.getMuteDays()).isEqualTo(30);
        assertThat(escalated.isMuting(NOW.plus(Duration.ofDays(29)))).isTrue();
    }

    @Test
    void lift_alreadyLifted_returnsZero() {
        // Arrange
        repository.upsert(RecommendationMute.create(USER_ID, COMBO, 7, NOW));

        // Act
        int first = repository.liftByUserAndCombo(USER_ID, COMBO);
        int second = repository.liftByUserAndCombo(USER_ID, COMBO);

        // Assert：LIFTED 再撤销 no-op（幂等直返 0）
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM recommendation_mute WHERE user_id = ? AND combo_key = ?",
                                String.class,
                                USER_ID,
                                COMBO))
                .isEqualTo(MuteStatus.LIFTED.name());
    }
}
