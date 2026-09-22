package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.ai.LlmUsage;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.infrastructure.common.RuntimeLlmGlobal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * LlmCostGuard 单测（T19+T30+T35）：单用户日 token 预算计数、超限拦截、<b>严格跨日重置</b>（T30 调优）与
 * <b>预算/告警阈值运行时热改</b>（T35：构造注入视图供应，改预算对下一次 checkBudget 立即生效，已用计数不清零）。
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

    /** 运行时全局视图（预算/阈值热改测试的可变承载）。 */
    private static RuntimeLlmGlobal global(long budget, double warnRatio) {
        return ConfigCenterStubs.global(30, budget, warnRatio);
    }

    @Test
    void recordUsage_accumulatesPerUserWithinSameDay() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(() -> global(20000, 0.8));
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
        LlmCostGuard guard = new LlmCostGuard(() -> global(20000, 0.8), clock);
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
        LlmCostGuard guard = new LlmCostGuard(() -> global(20000, 0.8));
        guard.recordUsage(USER, new LlmUsage(100, 0));

        // Act + Assert
        assertThatCode(() -> guard.checkBudget(USER)).doesNotThrowAnyException();
    }

    @Test
    void checkBudget_atBudget_throwsQuotaExhausted() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(() -> global(150, 0.8));
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
        LlmCostGuard guard = new LlmCostGuard(() -> global(100, 0.8));
        guard.recordUsage(USER, new LlmUsage(100, 0));
        guard.recordUsage(USER, new LlmUsage(50, 0)); // 累计 150 > 100

        // Act + Assert
        assertThatThrownBy(() -> guard.checkBudget(USER)).isInstanceOf(BusinessException.class);
    }

    @Test
    void userIdNonPositive_skipsBothCheckAndRecord() {
        // Arrange：预算 0——正常用户必抛
        LlmCostGuard guard = new LlmCostGuard(() -> global(0, 0.8));

        // Act + Assert：系统调用（无认证上下文）不限流
        assertThatCode(() -> guard.checkBudget(0L)).doesNotThrowAnyException();
        guard.recordUsage(0L, new LlmUsage(100, 100));
        assertThat(guard.currentUsage(0L)).isZero();
    }

    @Test
    void budgetPolicyPort_exposesBudgetAndWarnRatio() {
        // Arrange
        LlmCostGuard guard = new LlmCostGuard(() -> global(20000, 0.9));

        // Act + Assert：端口查询与报表同源（单一事实）
        assertThat(guard.dailyBudgetTokens()).isEqualTo(20000L);
        assertThat(guard.budgetWarnRatio()).isEqualTo(0.9);
    }

    @Test
    void budgetLoweredAtRuntime_nextCheckBlocksImmediatelyAndCountKept() {
        // Arrange（T35 热改，方案 §6「改预算 → 下一次 checkBudget 按新值拦截」）
        AtomicReference<RuntimeLlmGlobal> view = new AtomicReference<>(global(20000, 0.8));
        LlmCostGuard guard = new LlmCostGuard(view::get);
        guard.recordUsage(USER, new LlmUsage(100, 0)); // 已用 100

        // Act：页面把预算改小为 50（换视图引用，计数不清零）
        view.set(global(50, 0.8));

        // Assert：当次 checkBudget 即按新预算拦截，且已用计数保留 100
        assertThatThrownBy(() -> guard.checkBudget(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100/50");
        assertThat(guard.currentUsage(USER)).isEqualTo(100L);
    }

    @Test
    void budgetRaisedAtRuntime_nextCheckPassesAndReportSeesNewBudget() {
        // Arrange：预算 150 已用满并拦截
        AtomicReference<RuntimeLlmGlobal> view = new AtomicReference<>(global(150, 0.8));
        LlmCostGuard guard = new LlmCostGuard(view::get);
        guard.recordUsage(USER, new LlmUsage(150, 0));
        assertThatThrownBy(() -> guard.checkBudget(USER)).isInstanceOf(BusinessException.class);

        // Act：页面把预算调大到 500
        view.set(global(500, 0.8));

        // Assert：下一次放行；报表预算上限随改随新（故事 2 场景 2）
        assertThatCode(() -> guard.checkBudget(USER)).doesNotThrowAnyException();
        assertThat(guard.dailyBudgetTokens()).isEqualTo(500L);
    }

    @Test
    void warnRatioChangedAtRuntime_reportPolicyReflectsImmediately() {
        // Arrange
        AtomicReference<RuntimeLlmGlobal> view = new AtomicReference<>(global(20000, 0.8));
        LlmCostGuard guard = new LlmCostGuard(view::get);

        // Act：阈值 0.8 → 0.5
        view.set(global(20000, 0.5));

        // Assert
        assertThat(guard.budgetWarnRatio()).isEqualTo(0.5);
    }
}
