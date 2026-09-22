package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.application.ai.LlmCostReport.SceneCost;
import com.info.platform.application.ai.LlmCostReport.UserBudget;
import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmCostBudget;
import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LlmCostReportService 单测（T30）：窗口聚合主路径 + 空窗口边界 + 非法参数异常 + 预算状态分级。
 *
 * <p>仓储与预算端口用测试替身（领域端口接口，免 mock 框架）；时钟固定在 2026-09-22 12:00（Asia/Shanghai） 控制窗口边界与「今日」日界。
 */
class LlmCostReportServiceTest {

    /** 固定时钟：2026-09-22T12:00+08:00（今日起点 = 2026-09-21T16:00:00Z）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final Instant NOW = FIXED_CLOCK.instant();
    private static final Instant TODAY_START = Instant.parse("2026-09-21T16:00:00Z");

    /** 内存仓储：按 createdAt >= start 过滤、id 倒序。 */
    private static final class FakeRepository implements LlmCallLogRepository {
        private final List<LlmCallLog> rows = new ArrayList<>();
        private Instant lastQueryStart;

        @Override
        public LlmCallLog save(LlmCallLog log) {
            rows.add(log);
            return log;
        }

        @Override
        public List<LlmCallLog> findCreatedSince(Instant start, int limit) {
            lastQueryStart = start;
            return rows.stream()
                    .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isBefore(start))
                    .sorted(java.util.Comparator.comparing(LlmCallLog::getId).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    /** 预算策略替身：预算 1000、告警线 0.8×1000=800。 */
    private static final class StubBudget implements LlmCostBudget {
        @Override
        public void checkBudget(long userId) {
            // 预检不在本服务职责内
        }

        @Override
        public long dailyBudgetTokens() {
            return 1000L;
        }

        @Override
        public double budgetWarnRatio() {
            return 0.8;
        }
    }

    private static LlmCallLog row(
            long id,
            long userId,
            String provider,
            String scene,
            LlmCallStatus status,
            boolean cacheHit,
            int promptTokens,
            int completionTokens,
            long costMicros,
            Instant createdAt) {
        return LlmCallLog.reconstruct(
                id,
                userId,
                scene,
                provider,
                provider == null ? null : provider + "-model",
                status,
                cacheHit,
                promptTokens,
                completionTokens,
                costMicros,
                0L,
                null,
                createdAt);
    }

    private final FakeRepository repository = new FakeRepository();
    private final LlmCostReportService service =
            new LlmCostReportService(repository, new StubBudget(), FIXED_CLOCK);

    @Test
    void report_mixedRows_aggregatesTotalsRatesAndDistributions() {
        // Arrange：deepseek 成功（100+50=150token，300微元）、glm 缓存命中、glm 失败、预算拒绝
        repository.rows.add(
                row(1, 1001L, "deepseek", "1", LlmCallStatus.SUCCESS, false, 100, 50, 300L, NOW));
        repository.rows.add(
                row(2, 1001L, "deepseek", "1", LlmCallStatus.SUCCESS, true, 0, 0, 0L, NOW));
        repository.rows.add(row(3, 1001L, "glm", "4", LlmCallStatus.FAILED, false, 0, 0, 0L, NOW));
        repository.rows.add(row(4, 1002L, null, "3", LlmCallStatus.REJECTED, false, 0, 0, 0L, NOW));

        // Act
        LlmCostReport report = service.report("7d");

        // Assert：总调用 4、成功 2、失败 1、拒绝 1；成功率 2/(2+1)≈0.6667（拒绝不入分母）；命中率 1/4=0.25
        assertThat(report.totalCalls()).isEqualTo(4L);
        assertThat(report.successCalls()).isEqualTo(2L);
        assertThat(report.failedCalls()).isEqualTo(1L);
        assertThat(report.rejectedCalls()).isEqualTo(1L);
        assertThat(report.cacheHits()).isEqualTo(1L);
        assertThat(report.successRate()).isEqualTo(0.6667);
        assertThat(report.cacheHitRate()).isEqualTo(0.25);
        assertThat(report.totalTokens()).isEqualTo(150L);
        assertThat(report.costMicros()).isEqualTo(300L);
        assertThat(report.dailyBudgetTokens()).isEqualTo(1000L);
        assertThat(report.budgetWarnRatio()).isEqualTo(0.8);

        // Assert：provider 分布成本降序（0 成本按入库序：未发起在 glm 前）；null provider（拒绝）归「未发起」
        assertThat(report.providers()).hasSize(3);
        assertThat(report.providers().get(0).provider()).isEqualTo("deepseek");
        assertThat(report.providers().get(0).calls()).isEqualTo(2L);
        assertThat(report.providers().get(0).successCalls()).isEqualTo(2L);
        assertThat(report.providers().get(1).provider()).isEqualTo("未发起");
        assertThat(report.providers().get(1).calls()).isEqualTo(1L);
        assertThat(report.providers().get(2).provider()).isEqualTo("glm");
        assertThat(report.providers().get(2).failedCalls()).isEqualTo(1L);

        // Assert：场景分布
        assertThat(report.scenes())
                .extracting(SceneCost::scene)
                .containsExactlyInAnyOrder("1", "4", "3");
    }

    @Test
    void report_emptyWindow_returnsZeroedReport() {
        // Arrange：空窗口（无任何留痕）
        // Act
        LlmCostReport report = service.report("30d");

        // Assert：全零不抛（前端空态展示）
        assertThat(report.totalCalls()).isZero();
        assertThat(report.successRate()).isZero();
        assertThat(report.cacheHitRate()).isZero();
        assertThat(report.costMicros()).isZero();
        assertThat(report.providers()).isEmpty();
        assertThat(report.scenes()).isEmpty();
        assertThat(report.topUserBudgets()).isEmpty();
    }

    @Test
    void report_invalidWindow_throwsParamInvalid() {
        // Act + Assert：异常路径——非法时间窗 400（2001）
        assertThatThrownBy(() -> service.report("90d"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e ->
                                assertThat(((BusinessException) e).getErrorCode())
                                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("90d");
    }

    @Test
    void report_nullWindow_defaultsTo7d() {
        // Act
        LlmCostReport report = service.report(null);

        // Assert：缺省 7d，窗口起点 = now-7d
        assertThat(report.window()).isEqualTo("7d");
        assertThat(report.windowStart())
                .isEqualTo(NOW.minus(java.time.Duration.ofDays(7)).toString());
    }

    @Test
    void report_todayWindow_startsAtDayBoundaryOfSystemZone() {
        // Act
        LlmCostReport report = service.report("today");

        // Assert：日界取系统时区零点（12:00 CST → 当日 00:00 CST = 前日 16:00Z）
        assertThat(repository.lastQueryStart).isEqualTo(TODAY_START);
        assertThat(report.windowStart()).isEqualTo(TODAY_START.toString());
    }

    @Test
    void report_nonTodayWindow_budgetStillComputedFromTodayRows() {
        // Arrange：近 7 天有昨日旧数据，今日一条 700 token（用户 1001，告警线下）
        repository.rows.add(
                row(
                        1,
                        1001L,
                        "deepseek",
                        "1",
                        LlmCallStatus.SUCCESS,
                        false,
                        500,
                        200,
                        700L,
                        NOW.minusSeconds(3600)));
        repository.rows.add(
                row(
                        0,
                        1001L,
                        "deepseek",
                        "1",
                        LlmCallStatus.SUCCESS,
                        false,
                        100,
                        100,
                        200L,
                        TODAY_START.minusSeconds(60))); // 昨日：计入窗口不计入预算

        // Act
        LlmCostReport report = service.report("7d");

        // Assert：窗口 token 900，但预算只看今日 700（余量 300，未达 800 告警线 → NORMAL）
        assertThat(report.totalTokens()).isEqualTo(900L);
        assertThat(report.topUserBudgets()).hasSize(1);
        UserBudget budget = report.topUserBudgets().get(0);
        assertThat(budget.usedTokens()).isEqualTo(700L);
        assertThat(budget.remainingTokens()).isEqualTo(300L);
        assertThat(budget.status()).isEqualTo(LlmBudgetStatus.NORMAL);
    }

    @Test
    void report_budgetStatusGrades_warningAndExhausted() {
        // Arrange：用户 1001 用 850（越 800 告警线）、用户 1002 用 1000（耗尽）、用户 1003 用 50（正常）
        repository.rows.add(
                row(1, 1001L, "deepseek", "1", LlmCallStatus.SUCCESS, false, 850, 0, 850L, NOW));
        repository.rows.add(
                row(2, 1002L, "deepseek", "1", LlmCallStatus.SUCCESS, false, 1000, 0, 1000L, NOW));
        repository.rows.add(
                row(3, 1003L, "deepseek", "1", LlmCallStatus.SUCCESS, false, 50, 0, 50L, NOW));

        // Act
        List<UserBudget> budgets = service.report("today").topUserBudgets();

        // Assert：用量降序 + 状态分级（耗尽/告警/正常）；系统调用 userId=0 不入预算视图
        assertThat(budgets).extracting(UserBudget::userId).containsExactly(1002L, 1001L, 1003L);
        assertThat(budgets.get(0).status()).isEqualTo(LlmBudgetStatus.EXHAUSTED);
        assertThat(budgets.get(0).remainingTokens()).isZero();
        assertThat(budgets.get(1).status()).isEqualTo(LlmBudgetStatus.WARNING);
        assertThat(budgets.get(2).status()).isEqualTo(LlmBudgetStatus.NORMAL);
    }

    @Test
    void report_systemUserAndZeroUsageExcludedFromBudgets() {
        // Arrange：系统调用（userId=0，如政策倾向定时任务）与纯缓存命中用户（0 token）
        repository.rows.add(
                row(1, 0L, "glm", "3", LlmCallStatus.SUCCESS, false, 999, 999, 0L, NOW));
        repository.rows.add(
                row(2, 1001L, "deepseek", "1", LlmCallStatus.SUCCESS, true, 0, 0, 0L, NOW));

        // Act + Assert：预算视图为空（0 token 不产生预算压力）
        assertThat(service.report("today").topUserBudgets()).isEmpty();
    }

    @Test
    void report_usageCountedFromSuccessRowsOnly() {
        // Arrange：失败/拒绝行 token 为 0（实体工厂保证），仅成功行计预算
        LlmCallLog failed = LlmCallLog.begin(1001L, "1");
        failed.markFailed("所有 LLM provider 均失败", 100L);
        LlmCallLog rejected = LlmCallLog.begin(1001L, "2");
        rejected.markRejected("配额已用尽");
        repository.rows.add(failed);
        repository.rows.add(rejected);

        // Act + Assert
        assertThat(service.report("today").topUserBudgets()).isEmpty();
        assertThat(service.report("today").totalTokens()).isZero();
    }

    @Test
    void report_topUsersCappedToLimit() {
        // Arrange：6 个用户各 100 token（预算视图 Top 5）
        for (long userId = 1; userId <= 6; userId++) {
            repository.rows.add(
                    row(
                            userId,
                            userId,
                            "deepseek",
                            "1",
                            LlmCallStatus.SUCCESS,
                            false,
                            100,
                            0,
                            100L,
                            NOW));
        }

        // Act + Assert
        assertThat(service.report("today").topUserBudgets())
                .hasSize(LlmCostReportService.TOP_USERS);
    }
}
