package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmUsage;
import org.junit.jupiter.api.Test;

/**
 * LlmCallLogger 单测（T30）：留痕落库成功透传 + 落库失败仅记 ERROR 不阻断调用主链路。
 *
 * <p>仓储 mock（纯写入器，无状态逻辑）；异常不外抛是本类的核心契约（旁路可观测性不伤可用性）。
 */
class LlmCallLoggerTest {

    @Test
    void record_repositorySaves_succeeds() {
        // Arrange
        LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
        LlmCallLogger logger = new LlmCallLogger(repository);
        LlmCallLog entry = LlmCallLog.begin(1001L, "1");
        entry.markSuccess("deepseek", "deepseek-flash", new LlmUsage(100, 50), 300L, 100L);

        // Act + Assert：正常透传
        assertThatCode(() -> logger.record(entry)).doesNotThrowAnyException();
    }

    @Test
    void record_repositoryThrows_swallowsWithLoggingNotPropagating() {
        // Arrange：DB 抖动（留痕失败不应把 LLM 调用本身打挂）
        LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));
        LlmCallLogger logger = new LlmCallLogger(repository);
        LlmCallLog entry = LlmCallLog.begin(1001L, "1");
        entry.markFailed("所有 LLM provider 均失败", 30000L);

        // Act + Assert：异常被记录（ERROR，带上下文）且不向上抛
        assertThatCode(() -> logger.record(entry)).doesNotThrowAnyException();
    }
}
