package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.ai.LlmCallLog;
import com.info.platform.domain.ai.LlmCallLogRepository;
import com.info.platform.domain.ai.LlmCallStatus;
import com.info.platform.domain.ai.LlmUsage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * LlmCallLogRepositoryImpl 集成测试（T30）：SQLite 共享内存库 + Flyway（V13 建 llm_call_log 表）后，测 save 往返
 * 与 findCreatedSince 窗口过滤/排序/limit。
 *
 * <p>对齐 AiBriefRepositoryImplTest 模式（@SpringBootTest 全上下文 + @ActiveProfiles("test")）。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmCallLogRepositoryImplTest {

    @Autowired private LlmCallLogRepository repository;

    @Test
    void save_successRow_roundTripAndBackfillsId() {
        // Arrange
        LlmCallLog log = LlmCallLog.begin(1001L, "1");
        log.markSuccess("deepseek", "deepseek-flash", new LlmUsage(4000, 800), 7200L, 3200L);

        // Act
        LlmCallLog saved = repository.save(log);

        // Assert：回填 id/createdAt，各字段往返无损
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1001L);
        assertThat(saved.getProviderKey()).isEqualTo("deepseek");
        assertThat(saved.getModel()).isEqualTo("deepseek-flash");
        assertThat(saved.getSceneKey()).isEqualTo("1");
        assertThat(saved.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(saved.isCacheHit()).isFalse();
        assertThat(saved.getPromptTokens()).isEqualTo(4000);
        assertThat(saved.getCompletionTokens()).isEqualTo(800);
        assertThat(saved.getCostMicros()).isEqualTo(7200L);
        assertThat(saved.getDurationMillis()).isEqualTo(3200L);

        List<LlmCallLog> found = repository.findCreatedSince(Instant.now().minus(1, ChronoUnit.HOURS), 10);
        assertThat(found).anySatisfy(
                row -> {
                    assertThat(row.getId()).isEqualTo(saved.getId());
                    assertThat(row.isCacheHit()).isFalse();
                });
    }

    @Test
    void save_cacheHitAndFailedRows_persistFlagsAndError() {
        // Arrange
        LlmCallLog hit = LlmCallLog.begin(1002L, "4");
        hit.markCacheHit("glm", "glm-4-flash-250414", 0L);
        LlmCallLog failed = LlmCallLog.begin(0L, "3");
        failed.markFailed("所有 LLM provider 均失败：[deepseek, glm]", 30000L);

        // Act
        LlmCallLog savedHit = repository.save(hit);
        LlmCallLog savedFailed = repository.save(failed);

        // Assert
        assertThat(savedHit.isCacheHit()).isTrue();
        assertThat(savedHit.getCostMicros()).isZero();
        assertThat(savedFailed.getUserId()).isZero(); // 系统调用（定时任务无认证上下文）
        assertThat(savedFailed.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(savedFailed.getErrorMessage()).contains("deepseek");
    }

    @Test
    void findCreatedSince_filtersByWindowStartAndOrdersNewestFirst() {
        // Arrange：写入两条（时间戳由仓库取 Instant.now，倒序断言依赖插入顺序）
        LlmCallLog older = LlmCallLog.begin(1003L, "1");
        older.markSuccess("deepseek", "deepseek-flash", new LlmUsage(10, 5), 30L, 100L);
        LlmCallLog newer = LlmCallLog.begin(1003L, "2");
        newer.markRejected("配额已用尽");
        Long olderId = repository.save(older).getId();
        Long newerId = repository.save(newer).getId();

        // Act：窗口起点取未来时刻 → 空集；取过去 1h → 两条且 newest-first
        List<LlmCallLog> future = repository.findCreatedSince(Instant.now().plusSeconds(60), 10);
        List<LlmCallLog> past = repository.findCreatedSince(Instant.now().minus(1, ChronoUnit.HOURS), 10);

        // Assert
        assertThat(future).isEmpty();
        assertThat(past).extracting(LlmCallLog::getId).containsSubsequence(newerId, olderId);
    }

    @Test
    void findCreatedSince_limitCapsRows() {
        // Arrange
        for (int i = 0; i < 3; i++) {
            LlmCallLog log = LlmCallLog.begin(1004L, "1");
            log.markCacheHit("deepseek", "deepseek-flash", 0L);
            repository.save(log);
        }

        // Act
        List<LlmCallLog> rows = repository.findCreatedSince(Instant.now().minus(1, ChronoUnit.HOURS), 2);

        // Assert：上限护栏生效（newest-first 截断）
        assertThat(rows).hasSize(2);
    }
}
