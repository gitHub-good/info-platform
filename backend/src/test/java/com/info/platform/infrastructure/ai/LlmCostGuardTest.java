package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * LlmCostGuard 单测（T19）：单用户日 token 预算计数与超限拦截（Spike-2 §8.3）。
 *
 * <p>覆盖：用量累加 / 预算内放行 / 达上限抛 {@link BusinessException}({@link ErrorCode#AI_QUOTA_EXHAUSTED}) /
 * userId<=0 跳过（系统调用场景）。 不测 24h TTL（实测需等待）。
 */
class LlmCostGuardTest {

    private static final long USER = 1001L;

    @Test
    void recordUsage_accumulatesPerUser() {
        LlmCostGuard guard = new LlmCostGuard(20000);
        guard.recordUsage(USER, new LlmUsage(100, 50));
        guard.recordUsage(USER, new LlmUsage(200, 100));
        assertThat(guard.currentUsage(USER)).isEqualTo(450L);
    }

    @Test
    void checkBudget_underBudget_passes() {
        LlmCostGuard guard = new LlmCostGuard(20000);
        guard.recordUsage(USER, new LlmUsage(100, 0));
        assertThatCode(() -> guard.checkBudget(USER)).doesNotThrowAnyException();
    }

    @Test
    void checkBudget_atBudget_throwsQuotaExhausted() {
        LlmCostGuard guard = new LlmCostGuard(150);
        guard.recordUsage(USER, new LlmUsage(100, 50)); // 累计 150 == 预算
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
        LlmCostGuard guard = new LlmCostGuard(100);
        guard.recordUsage(USER, new LlmUsage(100, 0));
        guard.recordUsage(USER, new LlmUsage(50, 0)); // 累计 150 > 100
        assertThatThrownBy(() -> guard.checkBudget(USER)).isInstanceOf(BusinessException.class);
    }

    @Test
    void userIdNonPositive_skipsBothCheckAndRecord() {
        LlmCostGuard guard = new LlmCostGuard(0); // 预算 0：正常用户必抛
        assertThatCode(() -> guard.checkBudget(0L)).doesNotThrowAnyException();
        guard.recordUsage(0L, new LlmUsage(100, 100));
        assertThat(guard.currentUsage(0L)).isZero();
    }
}
