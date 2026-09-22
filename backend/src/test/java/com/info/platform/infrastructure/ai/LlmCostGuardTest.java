package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * LlmCostGuard 单测（T19+T30）：单用户日 token 预算计数、超限拦截、<b>严格跨日重置</b>（T30 调优， 日界键替代 24h TTL 近似）与预算策略端口查询。
 *
 * <p>不测 Caffeine 过期清理（实测需等待）；告警 WARN 日志为旁路观测，不进入断言（报表告警状态在 LlmCostReportServiceTest 覆盖同源阈值）。
 */
class LlmCostGuardTest {

    private static final long USER = 1001L;

    /** 可拨动时钟：模拟同一用户跨日调用（dayKey 换日即取新键）。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void recordUsage_accumulatesPerUserWithinSameDay() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(20000);
        guard.recordUsage(USER, new LlmUsage(100, 50));

        // Act
        guard.recordUsage(USER, new LlmUsage(200, 100));

        // Assert：同日累加 150+300
        assertThat(guard.currentUsage(USER)).isEqualTo(450L);
    }

    @Test
    void recordUsage_dayRolledOver_counterResetsStrictly() {
        // Arrange：2026-09-21 10:00（上海时区）起算
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T02:00:00Z"));
        LlmCostGuard guard = new LlmCostGuard(20000, 0.8, clock);
        guard.recordUsage(USER, new LlmUsage(100, 50));

        // Act：拨到次日 00:05（跨日），再记 50 token
        clock.advance(Duration.ofHours(15).plusMinutes(5));
        guard.recordUsage(USER, new LlmUsage(50, 0));

        // Assert：换日取新键，严格归零后累计（T19 的 24h TTL 近似做不到这一点）
        assertThat(guard.currentUsage(USER)).isEqualTo(50L);
    }

    @Test
    void checkBudget_underBudget_passes() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(20000);
        guard.recordUsage(USER, new LlmUsage(100, 0));

        // Act + Assert
        assertThatCode(() -> guard.checkBudget(USER)).doesNotThrowAnyException();
    }

    @Test
    void checkBudget_atBudget_throwsQuotaExhausted() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(150);
        guard.recordUsage(USER, new LlmUsage(100, 50)); // 累计 150 == 预算

        // Act + Assert
        assertThatThrownBy(() -> guard.checkBudget(USER))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> {
                            BusinessException be = (BusinessException) e;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXHAUSTED);
                        })
                .hasMessageContaining("150");
    }

    @Test
    void checkBudget_overBudget_throws() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(100);
        guard.recordUsage(USER, new LlmUsage(100, 0));
        guard.recordUsage(USER, new LlmUsage(50, 0)); // 累计 150 > 100

        // Act + Assert
        assertThatThrownBy(() -> guard.checkBudget(USER)).isInstanceOf(BusinessException.class);
    }

    @Test
    void userIdNonPositive_skipsBothCheckAndRecord() {
        // Arrange：预算 0——正常用户必抛
        LlmCostGuard guard = new LlmCostGuard(0);

        // Act + Assert：系统调用（无认证上下文）不限流
        assertThatCode(() -> guard.checkBudget(0L)).doesNotThrowAnyException();
        guard.recordUsage(0L, new LlmUsage(100, 100));
        assertThat(guard.currentUsage(0L)).isZero();
    }

    @Test
    void budgetPolicyPort_exposesBudgetAndWarnRatio() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(20000, 0.9, Clock.systemDefaultZone());

        // Act + Assert：端口查询与报表同源（单一事实）
        assertThat(guard.dailyBudgetTokens()).isEqualTo(20000L);
        assertThat(guard.budgetWarnRatio()).isEqualTo(0.9);
        assertThat(new LlmCostGuard(20000).budgetWarnRatio()).isEqualTo(0.8);
    }
}
