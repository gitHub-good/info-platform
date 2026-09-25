package com.info.platform.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.retention.ExpiredLogDeleter;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RetentionCleanupService 单测（T71，方案 §4.5 / §6 服务与 Job 组）：四表独立窗口同时生效 / 空轮 SUCCESS 语义（合计 0 不抛）/
 * 明细四段格式（段序=枚举序）/ 分批循环至返回 &lt; 500 / 每轮现读配置（含 Clock 注入的热改窗口）/ 配置缺失与非法值回退默认 /
 * 单表失败续跑其余表 + 轮末汇总抛出 + 部分计数入明细。AAA 结构（mock 删除端口与配置读取，固定 Clock）。
 */
class RetentionCleanupServiceTest {

    private static final String KEY = "retention.global";

    /** 固定时钟带亚秒精度——顺带验证 cutoff 整秒截断。 */
    private static final Instant NOW = Instant.parse("2026-09-22T03:30:00.123Z");

    private static final String DEFAULT_DOC =
            "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                    + "\"llmCallLogDays\":90,\"readingEventDays\":90}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
    private final ExpiredLogDeleter deleter = mock(ExpiredLogDeleter.class);
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        service =
                new RetentionCleanupService(
                        configService, deleter, Clock.fixed(NOW, Clock.systemUTC().getZone()));
    }

    private void stubConfig(String json) {
        try {
            when(configService.read(KEY))
                    .thenReturn(
                            Optional.of(
                                    new RuntimeConfigEntry(
                                            KEY,
                                            json,
                                            objectMapper.readTree(json),
                                            null,
                                            Instant.parse("2026-09-22T01:00:00Z"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void runOnce_fourIndependentWindows_eachTableCutOffByItsOwnWindow() {
        // Arrange：30/14 交叉窗口 + 40/50（各表窗口互不相同，验证独立判定）
        stubConfig(
                "{\"jobExecutionLogDays\":30,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":40,\"readingEventDays\":50}");
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.JOB_EXECUTION_LOG),
                        eq(Instant.parse("2026-08-23T03:30:00Z")),
                        eq(500)))
                .thenReturn(2L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.DATA_SOURCE_EVENT),
                        eq(Instant.parse("2026-09-08T03:30:00Z")),
                        eq(500)))
                .thenReturn(0L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.LLM_CALL_LOG),
                        eq(Instant.parse("2026-08-13T03:30:00Z")),
                        eq(500)))
                .thenReturn(1L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.READING_EVENT),
                        eq(Instant.parse("2026-08-03T03:30:00Z")),
                        eq(500)))
                .thenReturn(3L);

        // Act
        RetentionCleanupService.CleanupResult result = service.runOnce();

        // Assert：processed = 合计、明细四段（段序=枚举序、全表恒四段）
        assertThat(result.processedCount()).isEqualTo(6L);
        assertThat(result.detail())
                .isEqualTo(
                        "job_execution_log=2; data_source_event=0; llm_call_log=1; reading_event=3");
    }

    @Test
    void runOnce_emptyRound_processedZeroDetailAllZeros_noException() {
        // Arrange：四表全返回 0（空轮，PRD 场景 3）
        stubConfig(DEFAULT_DOC);
        when(deleter.deleteExpiredBefore(any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);

        // Act
        RetentionCleanupService.CleanupResult result = service.runOnce();

        // Assert：合计 0 照常返回（通道记 SUCCESS + processed_count=0）
        assertThat(result.processedCount()).isZero();
        assertThat(result.detail())
                .isEqualTo(
                        "job_execution_log=0; data_source_event=0; llm_call_log=0; reading_event=0");
    }

    @Test
    void runOnce_moreThanOneBatch_loopsUntilBatchReturnsLessThanLimit() {
        // Arrange：job_execution_log 三批 500+500+12（首轮 1.6 万行 ≈32 批的同构缩影）
        stubConfig(
                "{\"jobExecutionLogDays\":10,\"dataSourceEventDays\":14,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}");
        Instant cutoff = Instant.parse("2026-09-12T03:30:00Z");
        when(deleter.deleteExpiredBefore(eq(RetentionLogTable.JOB_EXECUTION_LOG), eq(cutoff), eq(500)))
                .thenReturn(500L, 500L, 12L);

        // Act
        RetentionCleanupService.CleanupResult result = service.runOnce();

        // Assert：三批循环（终批 12 < 500 即停）、合计准确、同 cutoff 同 limit
        verify(deleter, times(3))
                .deleteExpiredBefore(RetentionLogTable.JOB_EXECUTION_LOG, cutoff, 500);
        assertThat(result.processedCount()).isEqualTo(1012L);
        assertThat(result.detail()).startsWith("job_execution_log=1012; ");
    }

    @Test
    void runOnce_configKeyMissing_runsWithDefaults_neverZeroWindows() {
        // Arrange：键缺失（种子前/整键损坏）→ 全默认 30/14/90/90（降级预案：DB 无配置 = 用默认，不是全删）
        when(configService.read(KEY)).thenReturn(Optional.empty());
        when(deleter.deleteExpiredBefore(any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);

        // Act
        service.runOnce();

        // Assert：四表均按默认窗口截止（NOW=2026-09-22T03:30:00.123Z 整秒截断为 03:30:00Z）
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.JOB_EXECUTION_LOG,
                        Instant.parse("2026-08-23T03:30:00Z"),
                        500);
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.DATA_SOURCE_EVENT,
                        Instant.parse("2026-09-08T03:30:00Z"),
                        500);
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.LLM_CALL_LOG,
                        Instant.parse("2026-06-24T03:30:00Z"),
                        500);
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.READING_EVENT,
                        Instant.parse("2026-06-24T03:30:00Z"),
                        500);
    }

    @Test
    void runOnce_illegalValueBypassingValidator_fallsBackPerField() {
        // Arrange：jobExecutionLogDays 被直写为 0（绕过校验器）——执行侧回退 30，dataSourceEventDays=5 照常采信
        stubConfig(
                "{\"jobExecutionLogDays\":0,\"dataSourceEventDays\":5,"
                        + "\"llmCallLogDays\":90,\"readingEventDays\":90}");
        when(deleter.deleteExpiredBefore(any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);

        // Act
        service.runOnce();

        // Assert：job 表按 30 天判定（不是 0 天=全删，「设 0 清空」不可达）
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.JOB_EXECUTION_LOG,
                        Instant.parse("2026-08-23T03:30:00Z"),
                        500);
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.DATA_SOURCE_EVENT,
                        Instant.parse("2026-09-17T03:30:00Z"),
                        500);
    }

    @Test
    void runOnce_singleTableFails_continuesOthers_throwsAtEndWithBothSides() {
        // Arrange：llm_call_log 首批满批 500 行 → 循环继续，第二批抛错（部分计数 500 入明细）；其余三表正常
        // （PRD 故事 3 场景 4）
        stubConfig(DEFAULT_DOC);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.JOB_EXECUTION_LOG), any(Instant.class), eq(500)))
                .thenReturn(2L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.DATA_SOURCE_EVENT), any(Instant.class), eq(500)))
                .thenReturn(4L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.READING_EVENT), any(Instant.class), eq(500)))
                .thenReturn(1L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.LLM_CALL_LOG), any(Instant.class), eq(500)))
                .thenReturn(500L)
                .thenThrow(new IllegalStateException("db locked"));

        // Act + Assert：轮末汇总抛出（→ 通道记 FAILED + error_message）
        assertThatThrownBy(() -> service.runOnce())
                .isInstanceOf(RetentionCleanupException.class)
                .hasMessageContaining("llm_call_log")
                .hasMessageContaining("db locked")
                // 成功侧信息（含失败表已删批的部分计数）与失败原因同载
                .hasMessageContaining("job_execution_log=2")
                .hasMessageContaining("data_source_event=4")
                .hasMessageContaining("reading_event=1")
                .hasMessageContaining("llm_call_log=500");

        // 其余三表照删（失败不停摆）
        verify(deleter)
                .deleteExpiredBefore(
                        eq(RetentionLogTable.DATA_SOURCE_EVENT), any(Instant.class), eq(500));
        verify(deleter)
                .deleteExpiredBefore(
                        eq(RetentionLogTable.READING_EVENT), any(Instant.class), eq(500));
    }

    @Test
    void runOnce_nextRoundAfterFailure_runsNormally() {
        // Arrange：上一轮单表失败后，下一轮调度继续（REQ 非功能：失败轮不停摆）。
        // 先铺通用 0 行，再铺 LLM 专属链（Mockito 后铺的匹配桩胜出）：首轮即抛 → 次轮 0 行
        stubConfig(DEFAULT_DOC);
        when(deleter.deleteExpiredBefore(
                        any(RetentionLogTable.class), any(Instant.class), eq(500)))
                .thenReturn(0L);
        when(deleter.deleteExpiredBefore(
                        eq(RetentionLogTable.LLM_CALL_LOG), any(Instant.class), eq(500)))
                .thenThrow(new IllegalStateException("round-1 failure"))
                .thenReturn(0L);

        // Act：第一轮失败、第二轮成功
        assertThatThrownBy(() -> service.runOnce()).isInstanceOf(RetentionCleanupException.class);
        RetentionCleanupService.CleanupResult second = service.runOnce();

        // Assert：第二轮正常返回（无残留状态）
        assertThat(second.processedCount()).isZero();
        assertThat(second.detail())
                .isEqualTo(
                        "job_execution_log=0; data_source_event=0; llm_call_log=0; reading_event=0");
    }

    @Test
    void runOnce_readsConfigEveryRound_hotWindowChangeTakesEffectNextRound() {
        // Arrange：第一轮窗口 30 天
        stubConfig(DEFAULT_DOC);
        when(deleter.deleteExpiredBefore(any(RetentionLogTable.class), any(Instant.class), anyInt()))
                .thenReturn(0L);
        service.runOnce();
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.JOB_EXECUTION_LOG,
                        Instant.parse("2026-08-23T03:30:00Z"),
                        500);

        // Act：热改为 7 天（无重启、无事件依赖——用时读取即热）
        stubConfig(
                "{\"jobExecutionLogDays\":7,\"dataSourceEventDays\":2,"
                        + "\"llmCallLogDays\":35,\"readingEventDays\":35}");
        service.runOnce();

        // Assert：第二轮按新界 7 天判定（改大窗口不复活已删行——REQ 裁决，天然满足）
        verify(deleter)
                .deleteExpiredBefore(
                        RetentionLogTable.JOB_EXECUTION_LOG,
                        Instant.parse("2026-09-15T03:30:00Z"),
                        500);
        // 旧界 30 天仅第一轮使用一次
        verify(deleter, times(1))
                .deleteExpiredBefore(
                        RetentionLogTable.JOB_EXECUTION_LOG,
                        Instant.parse("2026-08-23T03:30:00Z"),
                        500);
    }
}
