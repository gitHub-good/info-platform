package com.info.platform.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * LlmCallLog 实体单测（T30）：四态定型工厂 + 成本估算换算 + 异常摘要截断。
 *
 * <p>AAA 结构；纯领域对象不依赖容器。
 */
class LlmCallLogTest {

    private static final long USER = 1001L;

    @Test
    void markSuccess_recordsProviderUsageAndCost() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(USER, "1");

        // Act
        log.markSuccess("deepseek", "deepseek-flash", new LlmUsage(4000, 800), 7200L, 3200L);

        // Assert
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(log.getProviderKey()).isEqualTo("deepseek");
        assertThat(log.getModel()).isEqualTo("deepseek-flash");
        assertThat(log.isCacheHit()).isFalse();
        assertThat(log.getPromptTokens()).isEqualTo(4000);
        assertThat(log.getCompletionTokens()).isEqualTo(800);
        assertThat(log.totalTokens()).isEqualTo(4800);
        assertThat(log.getCostMicros()).isEqualTo(7200L);
        assertThat(log.getDurationMillis()).isEqualTo(3200L);
        assertThat(log.getErrorMessage()).isNull();
    }

    @Test
    void markSuccess_nullUsageTreatedAsZero() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(USER, "4");

        // Act
        log.markSuccess("glm", "glm-4-flash-250414", null, 0L, 100L);

        // Assert
        assertThat(log.getPromptTokens()).isZero();
        assertThat(log.getCompletionTokens()).isZero();
        assertThat(log.totalTokens()).isZero();
    }

    @Test
    void markCacheHit_zeroTokenZeroCostWithOriginalProvider() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(USER, "1");

        // Act：缓存命中，provider/model 记原响应来源供回溯
        log.markCacheHit("deepseek", "deepseek-flash", 0L);

        // Assert
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(log.isCacheHit()).isTrue();
        assertThat(log.getProviderKey()).isEqualTo("deepseek");
        assertThat(log.totalTokens()).isZero();
        assertThat(log.getCostMicros()).isZero();
        assertThat(log.getDurationMillis()).isZero();
    }

    @Test
    void markFailed_keepsAttemptedInfoAndTruncates() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(USER, "3");
        String longMessage = "x".repeat(600);

        // Act
        log.markFailed(longMessage, 1500L);

        // Assert
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(log.getProviderKey()).isNull();
        assertThat(log.getDurationMillis()).isEqualTo(1500L);
        assertThat(log.getErrorMessage()).hasSize(500);
    }

    @Test
    void markRejected_recordsReasonWithoutProvider() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(USER, "2");

        // Act
        log.markRejected("用户 1001 当日 LLM token 配额已用尽");

        // Assert
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.REJECTED);
        assertThat(log.getProviderKey()).isNull();
        assertThat(log.getErrorMessage()).contains("配额已用尽");
        assertThat(log.getDurationMillis()).isZero();
    }

    @Test
    void begin_nullSceneKeyNormalizedToEmpty() {
        // Act
        LlmCallLog log = LlmCallLog.begin(0L, null);

        // Assert：userId=0 系统调用（如定时任务），sceneKey 规整空串
        assertThat(log.getUserId()).isZero();
        assertThat(log.getSceneKey()).isEmpty();
        assertThat(log.getStatus()).isNull();
    }

    @Test
    void estimateCostMicros_tokenTimesYuanPerMillionEqualsMicros() {
        // Arrange + Act：Spike-2 §8.1 基线——4000 输入 × 1 元/M + 800 输出 × 4 元/M = 7200 微元 = 0.0072 元
        long cost = LlmCallLog.estimateCostMicros(4000, 800, 1.0, 4.0);

        // Assert
        assertThat(cost).isEqualTo(7200L);
    }

    @Test
    void estimateCostMicros_unPricedOrNegativeProviderYieldsZero() {
        // Act + Assert：GLM 免费档未配单价（默认 0）不估算；负单价容错按 0
        assertThat(LlmCallLog.estimateCostMicros(4000, 800, 0, 0)).isZero();
        assertThat(LlmCallLog.estimateCostMicros(4000, 800, -1.0, 4.0)).isEqualTo(3200L);
    }
}
